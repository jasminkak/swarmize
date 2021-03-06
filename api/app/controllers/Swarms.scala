package controllers

import lib.{GeoJsonFormatter, Elasticsearch}
import lib.ElasticsearchPromise._
import org.elasticsearch.index.query.FilterBuilders
import org.elasticsearch.index.query.QueryBuilders._
import org.elasticsearch.indices.IndexMissingException
import org.elasticsearch.search.aggregations.AggregationBuilders
import org.elasticsearch.search.aggregations.bucket.terms.Terms
import org.elasticsearch.search.sort.SortOrder
import play.api.libs.json._
import play.api.mvc._
import swarmize.Swarm.{Closed, Open, Draft}
import swarmize._

import scala.collection.convert.wrapAll._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal

object Swarms extends Controller {


  private val corsHeaders = List(
    ACCESS_CONTROL_ALLOW_ORIGIN -> "*",
    ACCESS_CONTROL_ALLOW_METHODS -> "GET"
  )

  private def cacheTimeSecsFor(swarm: Swarm): Int = {
    swarm.status match {
      case Draft => 5
      case Open => 10
      case Closed => 60 * 60
    }
  }

  private def maxAgeSecs(secs: Int) = CACHE_CONTROL -> s"max-age=$secs"

  private def executeBlock(swarm: Swarm, emptyResponse: JsValue, block: Swarm => Future[JsValue]): Future[Result] = {
    block(swarm)
      .map { json => Ok(json)
        .withHeaders(corsHeaders: _*)
        .withHeaders(maxAgeSecs(cacheTimeSecsFor(swarm)))
        .withHeaders("X-Swarm-State" -> swarm.status.toString)
      }
      .recover {
        case e: IndexMissingException =>
          Ok(emptyResponse)
            .withHeaders(corsHeaders: _*)
            .withHeaders("X-No-Data-Reason" -> e.getMessage)
            .withHeaders(maxAgeSecs(cacheTimeSecsFor(swarm)))

        case NonFatal(e) =>
          InternalServerError("got an error " + e)
      }

  }

  private def swarmAction(token: String, emptyResponse: JsValue = Json.obj())(block: Swarm => Future[JsValue]) = Action.async { req =>
    try {
      val maybeApiKey = req.getQueryString("api_key")

      if (maybeApiKey.isEmpty) {
        Future.successful(Forbidden("api_key parameter required"))
      } else if (!SwarmApiKeys.isValid(token, maybeApiKey.get)) {
        Future.successful(Forbidden("this combination of api key and swarm token is not valid"))
      } else {
        Swarm.findByToken(token)
          .map(swarm => executeBlock(swarm, emptyResponse, block))
          .getOrElse(Future.successful(NotFound(s"Unknown swarm: $token")))
      }
    } catch {
      case e: RuntimeException =>
        Future.successful(InternalServerError(e.getMessage))
    }
  }

  def show(token: String) = swarmAction(token) { swarm =>
    Future.successful(swarm.definition.toJson)
  }

  def results(token: String, page: Int, pageSize: Int, format: Option[String], geo_json_point_key: Option[String], order_by: String) =
    swarmAction(token) { swarm =>

      val geojson = format contains "geojson"

      if (geojson && geo_json_point_key.isEmpty)
        sys.error("geo_json_point_key required for geojson format")

      val query =
        if (geojson)
          filteredQuery(matchAllQuery(), FilterBuilders.existsFilter(geo_json_point_key.get))
        else
          matchAllQuery()

      val ordering = order_by.toLowerCase match {
        case "newest" => SortOrder.DESC
        case _ => SortOrder.ASC
      }

      Elasticsearch.client.prepareSearch(swarm.indexName)
        .setSize(pageSize)
        .setFrom((page - 1) * pageSize)
        .addSort("timestamp", ordering)
        .setQuery(query)
        .execute().future map { results =>

        val srcDocs = results.getHits.hits().map(_.getSourceAsString).map(Json.parse)


        val result = if (geojson)
          GeoJsonFormatter.format(srcDocs.toList, geo_json_point_key.get)
        else
          Json.obj(
          "query_details" -> Json.obj(
            "per_page" -> pageSize,
            "page" -> page,
            "total_pages" -> ((results.getHits.getTotalHits / pageSize) + 1)
          ),
          "results" -> JsArray(srcDocs)
        )
        result
      }
    }

  def latest(token: String) = swarmAction(token) { swarm =>
    Elasticsearch.client.prepareSearch(swarm.indexName)
      .setSize(1)
      .setQuery(matchAllQuery())
      .addSort("timestamp", SortOrder.DESC)
      .execute().future map { results =>

      val srcDoc = results.getHits.hits().headOption.map(_.getSourceAsString).map(Json.parse)

      srcDoc getOrElse Json.obj()
    }
  }


  def counts(token: String) = swarmAction(token, emptyResponse = Json.arr()) { swarm =>

    val countableFields = swarm.allFields.collect {
      case b: BooleanField => b
      case pick: PickField => pick
    }

    if (countableFields.isEmpty)
      Future.successful(Json.arr())
    else {

      val req = Elasticsearch.client.prepareSearch(swarm.indexName)
        .setSize(0)
        .setQuery(matchAllQuery())

      countableFields.foreach { f =>
        req.addAggregation(AggregationBuilders.terms(f.codeName).field(f.codeName))
      }

      req.execute().future map { results =>
        val objs = results.getAggregations.asList zip countableFields map { case (agg, field) =>
          Json.obj(
            "field_name" -> field.fullName,
            "field_name_code" -> field.codeName,
            "counts" -> JsArray(
              agg.asInstanceOf[Terms].getBuckets.toSeq.map(b =>
                Json.obj(b.getKey -> b.getDocCount)
              )
            )
          )
        }

        JsArray(objs)
      }
    }
  }
}
