.PHONY: test eastwood cljfmt cloverage release deploy clean

VERSION ?= 1.10

test:
	set -e; set -x; \
	for v in "nrepl-0.6" "nrepl-0.7"; do \
	  lein with-profile -user,+$(VERSION),+$$v test; \
	done;

eastwood:
	lein with-profile -user,+$(VERSION),+eastwood eastwood

cljfmt:
	lein with-profile -user,+$(VERSION),+cljfmt cljfmt check

cloverage: SHELL := /bin/bash
cloverage:
	set -e; set -x; \
	for v in "nrepl-0.6" "nrepl-0.7"; do \
	  lein with-profile -user,+$(VERSION),+$$v,+cloverage cloverage; \
	  if [ ! -z $$CI ] ;\
	    then bash <(curl -s https://codecov.io/bash) -f target/coverage/codecov.json ; \
	  fi ; \
	done;

# When releasing, the BUMP variable controls which field in the
# version string will be incremented in the *next* snapshot
# version. Typically this is either "major", "minor", or "patch".

BUMP ?= patch

release:
	lein with-profile -user,+$(VERSION) release $(BUMP)

# Deploying requires the caller to set environment variables as
# specified in project.clj to provide a login and password to the
# artifact repository.

deploy:
	lein with-profile -user,+$(VERSION) deploy clojars

clean:
	lein clean
