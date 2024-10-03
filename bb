#!/bin/bash
set -efu

for opt do
        shift
        case "$opt" in
		--branch=* | --repo=*) export branch=${opt#*=} ;;
		--arch=* | --target=*) export set_target=${opt#*=} ;;
		-*) echo >&2 "unknown option: $opt"; exit 1 ;;
                *) set -- "$@" "$opt";;
        esac
done
type -p ts >/dev/null ||
	ts() { awk '{ print strftime("%T"), $0}'; }

[ -e .git ] || {
	echo >&2 "Not in a repo root."
	exit 1
}
[ -e kernel-image.spec ] && kflavour
sync

mkdir -p "${TMPDIR:-/tmp}/hasher"

L=log.$(date +%F_%H%M)
ln -sf "$L" -T log
{
	set -x
	git diff
	git log -1
} &> log
{ set -x
	gear-hsh ${*---commit}
} |& ts %T | tee -a log
beep
