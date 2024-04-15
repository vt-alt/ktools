#!/bin/bash

set -efu
workdir=$HOME/src/kernel-image

( cd "$workdir"
  set -x
  git fetch kernelbot )

for br in \
	    6.8/sisyphus	\
	    6.7/sisyphus	\
	 un-def/sisyphus	\
	std-def/sisyphus	\
	 un-def/p10		\
	std-def/p10		\
	 un-def/c10f2		\
	 un-def/c10f1		\
	 un-def/p9		\
	std-def/p9		\
	 un-def/p8		\
	std-def/p8		\
	std-def/c9f2		\
	rt/sisyphus		\
	rt/p10
do
	fn=logs/$br
	kbr=kernelbot/$br
	mkdir -p "$(dirname "$fn")"
	echo >&2 "Generating log ($fn) for $kbr..."
	# Remove empty lines between commit header and body.
	(cd "$workdir"; set -x; git log --format=fuller "$kbr") | sed '/^CommitDate:/{N;s/\n//}' > "$fn"
	# echo >&2 "Generating patch-id for $kbr..."
	# (cd "$workdir"; set -x; git log -p "$kbr" | git patch-id) > "$fn.id"
done

