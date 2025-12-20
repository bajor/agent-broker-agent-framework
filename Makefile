.PHONY: all build clean submit compile test
.PHONY: run-preprocessor run-codegen run-explainer run-refiner send-prompt
.PHONY: rabbit rabbit-remove distributed

all: build

build:
	sbt compile

compile: build

submit: build
	sbt "submit/run"

# ============ Distributed Agent Runners ============
# Each agent runs as a separate service listening on RabbitMQ

run-preprocessor: build
	sbt "runners/runMain com.llmagent.runners.PreprocessorRunner"

run-codegen: build
	sbt "runners/runMain com.llmagent.runners.CodeGenRunner"

run-explainer: build
	sbt "runners/runMain com.llmagent.runners.ExplainerRunner"

run-refiner: build
	sbt "runners/runMain com.llmagent.runners.RefinerRunner"

# Send a prompt to the distributed pipeline
# Usage: make send-prompt PROMPT=prime
send-prompt: build
	sbt "runners/runMain com.llmagent.runners.UserSubmit $(PROMPT)"

# Launch all agents in distributed mode
distributed: build
	./scripts/run-distributed.sh $(PROMPT)

# ============ RabbitMQ ============

rabbit:
	docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:management

rabbit-remove:
	docker stop rabbitmq && docker rm rabbitmq

test:
	sbt test

clean:
	sbt clean
	rm -rf target/
	rm -rf project/target/
	rm -rf common/target/
	rm -rf submit/target/
	rm -rf pipeline/target/
	rm -rf tools/target/
	rm -rf agents/target/
	rm -rf runners/target/
