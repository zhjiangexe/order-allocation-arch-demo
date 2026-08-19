SHELL := /bin/bash
.DEFAULT_GOAL := help

ENV ?= dev
DEPLOY := ./scripts/deploy.sh

.PHONY: help test check package push pull up deploy restart down logs ps config \
	dev-up dev-down stage-deploy stage-down prod-deploy prod-down

help:
	@printf '%s\n' \
		'make test                         Run backend unit tests' \
		'make check                        Run backend checks and SIT' \
		'make package ENV=dev|stage|prod   Build the monolith image' \
		'make push ENV=stage|prod          Push the selected image' \
		'make up ENV=dev|stage|prod        Start an image already available locally' \
		'make deploy ENV=stage|prod        Pull and start an immutable image' \
		'make down|logs|ps|config ENV=...  Operate the selected environment' \
		'make dev-up                       Build and start the complete local stack'

test:
	./backend/gradlew -p backend test

check:
	./backend/gradlew -p backend check

package push pull up deploy restart down logs ps config:
	$(DEPLOY) $(ENV) $@

dev-up:
	$(DEPLOY) dev up

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
