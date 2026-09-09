SHELL := /bin/bash
.DEFAULT_GOAL := help

ENV ?= dev
MODE ?= events
DEPLOY := ./scripts/deploy.sh

.PHONY: demo-up demo-down demo-restart demo-logs demo-ps demo-config demo-events demo-temporal help test check e2e package push pull up deploy restart down logs ps config \
	dev-up dev-up-temporal dev-down stage-deploy stage-down prod-deploy prod-down

help:
	@printf '%s\n' \
		'make demo-up MODE=events|temporal  Start presentation frontend and full backend stack' \
		'make demo-down MODE=...            Stop presentation stack; retain data' \
		'make demo-restart|demo-logs|demo-ps MODE=...  Operate presentation stack' \
		'make test                         Run backend unit tests' \
		'make check                        Run backend checks and SIT' \
		'make e2e                          Run isolated Karate v2 Events and Temporal E2E' \
		'make package ENV=dev|stage|prod   Build the monolith image' \
		'make push ENV=stage|prod          Push the selected image' \
		'make up ENV=dev|stage|prod        Start an image already available locally' \
		'make deploy ENV=stage|prod        Pull and start an immutable image' \
		'make down|logs|ps|config ENV=...  Operate the selected environment' \
		'make dev-up                       Build and start the complete local stack' \
		'make dev-up-temporal              Start dev with Temporal as fulfillment driver'

test:
	./backend/gradlew -p backend test

check:
	./backend/gradlew -p backend check

e2e:
	./e2e/spec/run.sh

package push pull up deploy restart down logs ps config:
	$(DEPLOY) $(ENV) $@

dev-up:
	$(DEPLOY) dev up

dev-up-temporal:
	ORDER_PROMISING_FULFILLMENT_ORCHESTRATION_MODE=temporal $(DEPLOY) dev up

dev-down:
	$(DEPLOY) dev down

stage-deploy:
	$(DEPLOY) stage deploy

stage-down:
	$(DEPLOY) stage down

prod-deploy:
	$(DEPLOY) prod deploy

prod-down:
	$(DEPLOY) prod down

# Demo stacks are isolated from dev and from each other.
demo-up demo-down demo-restart demo-logs demo-ps demo-config:
	./scripts/demo.sh $(patsubst demo-%,%,$@) $(MODE)

demo-events:
	./scripts/demo.sh up events

demo-temporal:
	./scripts/demo.sh up temporal
