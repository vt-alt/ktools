
install:
	git ls-files | xargs grep -l '^#!/bin/bash' | xargs -i ln -vrsf {} $(HOME)/bin/{}
