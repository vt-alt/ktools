#!/bin/bash
set -efu +o posix

fatal() {
	echo >&2 "! $*"
	exit 1
}

pkgi=()
for opt do
        shift
        case "$opt" in
		+32)  targets+=("$HOSTTYPE") ;&
		--32) targets+=('i586') ;;
		--s | --sisyphus) branches+=("sisyphus") ;;
		--[cp][[:digit:]]*) branches+=(${opt#--}) ;;
		--branch=* | --repo=*) branches+=(${opt#*=}) ;;
		--arch=* | --target=*) targets+=( "${opt#*=}" ) ;;
		--task=*) task="${opt#*=}" ;;
		--ini*) initroot=only ;;
		--no-ini*) noinitroot=ci ;;
		--inst*=*|--ci=*) pkgi+=("${opt#*=}") ;;
		--date=*|--archive=*) archive_date=${opt#*=} ;;
		--components=*) components=${opt#*=} ;;
		--) break ;;
		-*) fatal "Unknown option: $opt" ;;
                *) set -- "$@" "$opt";;
        esac
done
type -p ts >/dev/null ||
	ts() { awk '{ print strftime("%T"), $0}'; }

toplevel=$(git rev-parse --show-toplevel)
[ "$toplevel" -ef . ] ||{
       	echo "+ cd $toplevel"
	cd "$toplevel"
}

[ -v branches ] || branches=("sisyphus")
for branch in "${branches[@]}"; do
	[ -v branch ] && [ ! -d "/ALT/$branch" ] && fatal "Unknown branch=$branch."
done

[ -v targets ] || targets=("$HOSTTYPE")
for set_target in "${targets[@]}"; do
	[ ! -f "/ALT/${branch-sisyphus}/$set_target/base/release" ] && fatal "Unknown target=$set_target."
done

if [ -v archive_date ]; then
	archive_date=${archive_date//-/\/}
	if [[ $archive_date == */*/* ]]; then
		:
	elif [[ $archive_date == */* ]]; then
		archive_date+="/01"
	else
		archive_date+="/01/01"
	fi
fi

[ -e kernel-image.spec ] && kflavour
sync

mkdir -p "${TMPDIR:-/tmp}/hasher"

log_config() { echo "+ branch=${branch-} target=${set_target-} date=${archive_date-} task=${task-}"; }

if [ -n "${initroot-}" ]; then
	log_config
	(set -x; hsh --initroot)
	exit
fi

if [ -d .git ] && [ ! -d .git/bb ]; then
        mkdir .git/bb
	if [[ -n $(set +f; ls log.2024* 2>/dev/null) ]]; then
		(set +f; mv -v log.2024* -t .git/bb)
	fi
fi

set -o pipefail
aterr() {
	echo "FAILED ${branch-} ${set_target-}"
}
trap 'beep' EXIT
trap '{ set +x; } 2>/dev/null; aterr' ERR
sep=

for target in "${targets[@]}"; do
for branch in "${branches[@]}"; do
	set_target=$target
	export branch set_target archive_date task

	L=.git/bb/log.$(date +%F_%H%M)
	[ "sisyphus" = "$branch" ] && unset branch || L+=".$branch"
	[ "$HOSTTYPE" = "$set_target" ] && unset set_target || L+=".$set_target"
	ln -sf "$L" -T log

	printf '%s' "$sep"
	{
		set -x
		git diff
		# shellcheck disable=SC2094
		git 'log' -1
	} &> log
	{
		{ log_config; } 2>/dev/null
		# shellcheck disable=SC2086,SC2048
		gear-hsh ${*---commit}
	} |& {
		{ set +x; } 2>/dev/null
		ts %T | tee -a log
	}
	{ set +x; } 2>/dev/null
	if [[ ${#pkgi[@]} -gt 0 ]]; then
		( cd /var/empty
		  [ -n "${noinitroot-}" ] || (echo; set -x; hsh --initroot)
		  echo
		  set -x
		  # shellcheck disable=SC2068
		  hsh-install ${pkgi[@]}
		) |& ts %T | tee -a log
	fi
	sep=$'\n'
done
done
