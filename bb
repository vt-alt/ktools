#!/bin/bash
set -efu +o posix

fatal() {
	echo >&2 "! $*"
	exit 1
}

pkgi=()
commit=("--commit")
ts='%T'
for opt do
        shift
	arg=${opt#*=}
        case "$opt" in
		+32)  targets+=("$HOSTTYPE") ;&
		-32 | --32) targets+=('i586') ;;
		--s | --sisyphus) branches+=("sisyphus") ;;
		--[cp][[:digit:]]*) branches+=(${opt#--}) ;;
		--branch=* | --repo=*) branches+=(${arg//,/ }) ;;
		--arch=* | --target=*) targets+=( "${opt#*=}" ) ;;
		--task=*) task="${opt#*=}" ;;
		--build-srpm-only | -bs) gear_hsh=("hsh" "--build-srpm-only") ;;
		--install-only) gear_hsh=("hsh-rebuild" "$opt") ;;
		--ini*) initroot=only ;;
		--no-ini*) noinitroot=ci ;;
		--rpmi=*|--ci=*) pkgi+=(${arg//,/ }) ;;
		--no-beep) NOBEEP=y ;;
		--ci) ci=checkinstall ;;
		--ci-all) ci=all ;;
		--ci-command=*) ci_command="${opt#*=}" ;;
		--clean) hsh_clean=y ;;
		--fresh) fresh=y ;;
		--date=*|--archive=*) archive_date=${opt#*=} ;;
		--components=*) components=${opt#*=} ;;
		--disable[-=]*) set_rpmargs+="--disable ${opt#--*[-=]}" ;;
		--kflavour=*) kflavour=${opt#*=} ;;
		--tree-ish=* | -t=*) commit=("-t" "${opt#*=}") ;;
		--ts=*) ts=${opt#*=} ;;
		--) break ;;
		-*) fatal "Unknown option: $opt" ;;
                *) set -- "$@" "$opt";;
        esac
done
unset opt arg
type -p ts >/dev/null ||
	ts() { awk -v t="${1-%T}" '{ print strftime(t), $0}; fflush()'; }

[ "${bb_ts-}" = pwd ] && ts="($(basename "$PWD"))"

if [ ! -v branches ]; then
	if [ -v branch ]; then
		: # From upper level bb run.
	elif [ -v task ]; then
		branch=$(curl -sSLf "https://git.altlinux.org/tasks/$task/task/repo")
	else
		branch="sisyphus"
	fi
	branches=("$branch")
fi
for branch in "${branches[@]}"; do
	[ "$branch" = 's' ] && branch=sisyphus
	[ ! -d "/ALT/$branch" ] && fatal "Unknown branch=$branch."
done

if [ ! -v targets ]; then
	[ -v set_target ] && targets=("$set_target") || targets=("$HOSTTYPE")
fi
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

mkdir -p "${TMPDIR:-/tmp}/hasher"

log_config() {
	echo "+ branch=${branch-} target=${set_target-} date=${archive_date-} task=${task-}"
	if [ -v set_rpmargs ]; then echo "+ rpmargs=$set_rpmargs"; fi
}

pkg_install() {
	build_state="CI initroot"
	if [ -v fresh ]; then
		echo ":: CI ${branch-Sisyphus} packages one by one: ${pkgi[*]}"
		for pkg in "${pkgi[@]}"; do
			(echo; set -x; hsh --initroot)
			build_state="CI install-one $pkg"
			echo
			cd /var/empty
			(set -x; hsh-install "$pkg")
		done
	else
		echo
		echo ":: CI ${branch-Sisyphus} packages all at once: ${pkgi[*]}"
		[ -n "${noinitroot-}" ] || (echo; set -x; hsh --initroot)
		echo
		build_state="CI install-all ${pkgi[*]}"
		((!${#pkgi[@]})) || (
			cd /var/empty
			set -x
			hsh-install "${pkgi[@]}"
		)
	fi
	unset build_state
}

repo_clean() {
	mkdir -p ~/repo
	rm -rf -v ~/repo/*
}
[ -v hsh_clean ] && repo_clean

export branch set_target archive_date task components set_rpmargs
if [ -n "${initroot-}" ]; then
	log_config
	pkg_install
	exit
elif [ -v gear_hsh ]; then
	log_config
	(set -x; gear --hasher "${commit[@]}" -- "${gear_hsh[@]}")
	pkg_install
	exit
fi

toplevel=$(git rev-parse --show-toplevel)
[ "$toplevel" -ef . ] ||{
	echo "+ cd $toplevel"
	cd "$toplevel"
}

if [ -d .git ] && [ ! -d .git/bb ]; then
        mkdir .git/bb
	if [[ -n $(set +f; ls log.2024* 2>/dev/null) ]]; then
		(set +f; mv -v log.2024* -t .git/bb)
	fi
fi

# shellcheck disable=SC2086
[ -e kernel-image.spec -o -v kflavour ] && kflavour ${kflavour-}
sync

set -o pipefail
unset build_state
aterr() {
	local red=$'\e[1;31m' norm=$'\e[m'
	echo "${red}FAILED ($(basename "$PWD")) ${branch-} ${set_target-} state=${build_state-}${norm}"
}
[ -v NOBEEP ] || trap 'beep' EXIT
trap '{ set +x; } 2>/dev/null; aterr' ERR
sep=

for target in "${targets[@]}"; do
for branch in "${branches[@]}"; do
	[ "$branch" = 's' ] && branch=sisyphus
	set_target=$target
	# Reexport, since we did unset inside of the loop.
	export branch set_target

	for log in log log1 .log build.log; do
		[[ -d "$log" ]] || break
	done
	L=.git/bb/log.$(date +%F_%H%M)
	[ "sisyphus" = "$branch" ] && unset branch || L+=".$branch"
	[ "$HOSTTYPE" = "$set_target" ] && unset set_target || L+=".$set_target"
	ln -sf "$L" -T "$log"

	[ -v hsh_clean ] && repo_clean
	printf '%s' "$sep"
	build_state="gear-hsh"
	{
		set -x
		git diff
		# shellcheck disable=SC2094
		git 'log' -1
	} &> "$log"
	{
		{ log_config; } 2>/dev/null
		gear-hsh "${commit[@]}" "${@}"
	} |& {
		{ set +x; } 2>/dev/null
		ts "$ts" | tee -a "$log"
	}
	{ set +x; } 2>/dev/null
	if [ -v ci_command ]; then
		echo
		echo ":: CI commands for ${branch-Sisyphus}:"
		build_state="ci-command"
		export NOBEEP=y bb_ts=pwd
		(eval "set -xe; $ci_command")
		unset build_state
		echo
	fi |& ts "$ts" | tee -a "$log"
	if [ -v ci ]; then
		mapfile -t pkgi < <(
			if [[ $ci == checkinstall ]]; then
				sed -En 's!^\S+ Wrote:\s/usr/src/RPM/RPMS/[[:graph:]/]+/(\S+-checkinstall)-\S+\.rpm\s.*!\1!p'
			elif [[ $ci == all ]]; then
				sed -En 's!^\S+ Wrote:\s/usr/src/RPM/RPMS/[[:graph:]/]+/(\S+)-[^-]+-alt\S+\.rpm\s.*!\1!p' |
				grep -v '-debuginfo'
			fi < log
		)
	fi
	((${#pkgi[@]})) &&
		pkg_install |& ts "$ts" | tee -a "$log"
	sep=$'\n'
done
done
