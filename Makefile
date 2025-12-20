.PHONY: all build clean submit compile test
.PHONY: run-preprocessor run-codegen run-explainer run-refiner send-prompt
.PHONY: rabbit rabbit-remove distributed

all: build

build:
	sbt compile

compile: build

submit: build
	sbt "submit/run"

# ============ Distributed Agent Runners (DSL-based) ============
# Each agent runs as a separate service listening on RabbitMQ

run-preprocessor: build
	sbt "examples/runMain com.llmagent.examples.PreprocessorMain"

run-codegen: build
	sbt "examples/runMain com.llmagent.examples.CodeGenMain"

run-explainer: build
	sbt "examples/runMain com.llmagent.examples.ExplainerMain"

run-refiner: build
	sbt "examples/runMain com.llmagent.examples.RefinerMain"

# Send a prompt to the distributed pipeline
# Usage: make send-prompt PROMPT=prime
send-prompt: build
	sbt "examples/runMain com.llmagent.examples.UserSubmit $(PROMPT)"

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
	rm -rf tools/target/
	rm -rf dsl/target/
	rm -rf examples/target/
