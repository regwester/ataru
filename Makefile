EXECUTABLES = lein docker docker-compose npm lftp

VIRKAILIJA_DEV_CONFIG=../ataru-secrets/virkailija-local-dev.edn
HAKIJA_DEV_CONFIG=../ataru-secrets/hakija-local-dev.edn

HAKIJA_FRONTEND_COMPILER=ataru-hakija-frontend-compilation
VIRKAILIJA_FRONTEND_COMPILER=ataru-virkailija-frontend-compilation
FIGWHEEL=ataru-figwheel
CSS_COMPILER=ataru-css-compilation
HAKIJA_BACKEND=ataru-hakija-backend-8351
VIRKAILIJA_BACKEND=ataru-virkailija-backend-8350

PM2=npx pm2 --no-autorestart
START_ONLY=start pm2.config.js --only
STOP_ONLY=stop pm2.config.js --only

DOCKER_COMPOSE=COMPOSE_PARALLEL_LIMIT=8 docker-compose

NODE_MODULES=node_modules/pm2/bin/pm2

# ----------------
# Check ataru-secrets existence and config files
# ----------------
ifeq ("$(wildcard $(VIRKAILIJA_DEV_CONFIG))","")
    $(error $(VIRKAILIJA_DEV_CONFIG) not found, clone/update ataru-secrets alongside ataru since configs are stored there)
endif

ifeq ("$(wildcard $(HAKIJA_DEV_CONFIG))","")
    $(error $(HAKIJA_DEV_CONFIG) not found, clone/update ataru-secrets alongside ataru since configs are stored there)
endif

# ----------------
# Check that all necessary tools are in path
# ----------------
check-tools:
	$(info Checking commands in path: $(EXECUTABLES) ...)
	$(foreach exec,$(EXECUTABLES),\
		$(if $(shell which $(exec)),$(info .. $(exec) found),$(error No $(exec) in PATH)))

# ----------------
# Docker build
# ----------------
build-docker-images: check-tools
	$(DOCKER_COMPOSE) build

# ----------------
# Npm installation
# ----------------
$(NODE_MODULES):
	npm install

# ----------------
# Start apps
# ----------------
start-docker: build-docker-images
	$(DOCKER_COMPOSE) up -d

start-pm2: $(NODE_MODULES) start-docker
	$(PM2) start pm2.config.js

start-hakija-frontend-compilation: $(NODE_MODULES)
	$(PM2) $(START_ONLY) $(HAKIJA_FRONTEND_COMPILER)

start-virkailija-frontend-compilation: $(NODE_MODULES)
	$(PM2) $(START_ONLY) $(VIRKAILIJA_FRONTEND_COMPILER)

start-watch: $(NODE_MODULES) start-hakija-frontend-compilation start-virkailija-frontend-compilation
	$(PM2) $(START_ONLY) $(FIGWHEEL)
	$(PM2) $(START_ONLY) $(CSS_COMPILER)

start-hakija: start-hakija-frontend-compilation start-docker
	$(PM2) $(START_ONLY) $(HAKIJA_BACKEND)

start-virkailija: start-virkailija-frontend-compilation start-docker
	$(PM2) $(START_ONLY) $(VIRKAILIJA_BACKEND)

# ----------------
# Stop apps
# ----------------
stop-pm2: $(NODE_MODULES)
	$(PM2) stop pm2.config.js

stop-hakija-frontend-compilation:
	$(PM2) $(STOP_ONLY) $(HAKIJA_FRONTEND_COMPILER)

stop-virkailija-frontend-compilation:
	$(PM2) $(STOP_ONLY) $(VIRKAILIJA_FRONTEND_COMPILER)

stop-watch: stop-hakija-frontend-compilation stop-virkailija-frontend-compilation
	$(PM2) $(STOP_ONLY) $(FIGWHEEL)
	$(PM2) $(STOP_ONLY) $(CSS_COMPILER)

stop-docker:
	docker-compose down

stop-hakija:
	$(PM2) $(STOP_ONLY) $(HAKIJA_BACKEND)

stop-virkailija:
	$(PM2) $(STOP_ONLY) $(VIRKAILIJA_BACKEND)

# ----------------
# Restart apps
# ----------------
restart-hakija: start-hakija

restart-virkailija: start-virkailija

restart-docker: stop-docker start-docker

restart-watch: start-watch

# ----------------
# Clean commands
# ----------------
clean-docker:
	docker system prune -f

clean-lein:
	lein clean

# ----------------
# Top-level commands (all apps)
# ----------------
start: start-pm2

stop: stop-pm2

restart: stop-pm2 start-pm2

clean: stop clean-lein clean-docker
	rm -rf node_modules
	rm *.log

status: $(NODE_MODULES)
	docker ps
	$(PM2) status

log: $(NODE_MODULES)
	$(PM2) logs

help:
	@cat Makefile.md

# ----------------
# Test db management
# ----------------

test: start-docker
	CONFIG=config/test.edn ./bin/cibuild.sh run-tests	

# ----------------
# Kill PM2 and all apps managed by it (= everything)
# ----------------
kill: stop-pm2 stop-docker
	$(PM2) kill

