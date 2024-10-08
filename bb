#!/bin/bash
set -efu

fatal() {
	echo >&2 "! $*"
	exit 1
}

for opt do
        shift
        case "$opt" in
		--32) export set_target=i586 ;;
		--[cp][[:digit:]]*) export branch=${opt#--} ;;
		--branch=* | --repo=*) export branch=${opt#*=} ;;
		--arch=* | --target=*) export set_target=${opt#*=} ;;
		--task=*) export task="${opt#*=}" ;;
		-*) fatal "Unknown option: $opt" ;;
                *) set -- "$@" "$opt";;
        esac
done
type -p ts >/dev/null ||
	ts() { awk '{ print strftime("%T"), $0}'; }

[ -e .git ] || fatal "Not in a repo root."

[ -v branch ] && [ ! -d "/ALT/$branch" ] && fatal "Unknown branch=$branch."
[ -v set_target ] && [ ! -d "/ALT/${branch-sisyphus}/base/release" ] && fatal "Unknwon target=$set_target."

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
{
	{ echo + branch=${branch-} target=${set_target-} task=${task-}; } 2>/dev/null
	gear-hsh ${*---commit}
} |& {
	{ set +x; } 2>/dev/null
	ts %T | tee -a log
}
beep
