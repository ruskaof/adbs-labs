.PHONY: run rund services stop clean gen load setup teardown
.DEFAULT_GOAL = run
COMPOSE := docker compose --env-file .env.local
PYTHON ?= python3
DATASET := $(PYTHON) scripts/dataset.py

run:
	$(COMPOSE) up -d --build --wait

rund:
	$(COMPOSE) up --build

services:
	$(COMPOSE) ps

stop:
	$(COMPOSE) down

clean:
	$(COMPOSE) down -v

gen:
	$(DATASET) gen

load:
	$(DATASET) load

setup:
	$(DATASET) setup

teardown:
	$(DATASET) teardown
