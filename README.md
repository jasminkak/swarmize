# Swarmize

Swarmize is a stack of tools to make crowd-powered number-gathering a lot easier.

Currently in this repository:

* `api`: The Scala-based API
* `bombard`: A small script for filling a Swarm with fake data
* `case_studies`: the case studies for the project, which are then copied into the Rails app for deployment (but will work fine on Github as they are)
* `cloudformation`: AWS CloudFormation config
* `collector`: Receive data from an endpoint and forward it to the stream. [scala]
* `deploy`: The deploy tools/scripts
* `examples`: Examples of Collector and Retrieval API usage
* `geocoder`: Simple Node geocoder endpoint [node]
* `livedebate`: Sample app: click on the face you're liking most at any point during a prime ministerial debate; the data - along with some demographic information - will be pushed into Swarm. [ruby]
* `processor`: Processes submitted data [scala]
* `sentiment-endpoint`: Simple Node sentiment analysis endpoint [node]
* `swarmize-dot-com`: the Swarmize alpha website. [ruby]

[![Build Status](https://travis-ci.org/guardian/swarmize.svg?branch=master)](https://travis-ci.org/guardian/swarmize)
