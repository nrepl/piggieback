.PHONY: test eastwood cljfmt cloverage release deploy clean

VERSION ?= 1.9

# Some tests need to be filtered based on JVM version.  This selector
# will be mapped to a function in project.clj, and that function
# determines which `deftest` to run based on their metadata.
JAVA_VERSION := $(shell lein with-profile +sysutils \
                        sysutils :java-version-simple | cut -d " " -f 2)

test:
	set -e; set -x; \
	for v in "nrepl-0.4" "nrepl-0.5" "nrepl-0.6"; do \
	  lein with-profile +$(VERSION),+$$v test; \
	done;

eastwood:
	lein with-profile +$(VERSION),+eastwood eastwood

cljfmt:
	lein with-profile +$(VERSION),+cljfmt cljfmt check

cloverage: SHELL := /bin/bash
cloverage:
	set -e; set -x; \
	for v in "nrepl-0.4" "nrepl-0.5" "nrepl-0.6"; do \
	  lein with-profile +$(VERSION),+$$v,+cloverage cloverage; \
	  if [ -n $CI ] ; \
	    then bash <(curl -s https://codecov.io/bash) -f target/coverage/codecov.json ; \
	  fi ; \
	done;

# When releasing, the BUMP variable controls which field in the
# version string will be incremented in the *next* snapshot
# version. Typically this is either "major", "minor", or "patch".

BUMP ?= patch

release:
	lein with-profile +$(VERSION) release $(BUMP)

# Deploying requires the caller to set environment variables as
# specified in project.clj to provide a login and password to the
# artifact repository.

deploy:
	lein with-profile +$(VERSION) deploy clojars

clean:
	lein clean
