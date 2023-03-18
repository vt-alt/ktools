
install:
	git ls-files | xargs grep -l '^#!.*/bin/' | xargs -i ln -vrsf {} $(HOME)/bin/{}
