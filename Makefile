.PHONY: test eastwood cljfmt cljfmt-fix kondo lint release deploy clean

VERSION ?= 1.12
NREPL_VERSION ?= nrepl-1.0

test:
	lein with-profile -user,+$(VERSION),+$(NREPL_VERSION) test;

eastwood:
	lein with-profile -user,+$(VERSION),+eastwood eastwood

cljfmt:
	lein with-profile -user,+$(VERSION),+cljfmt cljfmt check

cljfmt-fix:
	lein with-profile -user,+$(VERSION),+cljfmt cljfmt fix

kondo:
	clj-kondo --lint src test

lint: cljfmt eastwood kondo


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
