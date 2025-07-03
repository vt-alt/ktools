#!/bin/bash
set -efu +o posix
shopt -s extglob

fatal() {
	echo >&2 "! $*"
	exit 1
}

pkgi=()
commit=("--commit")
ts='%T'
wait_lock="--no-wait-lock"
for opt do
        shift
	arg=${opt#*=}
        case "$opt" in
		+32)  targets+=("$HOSTTYPE") ;&
		-32 | --32) targets+=('i586') ;;
		--arch=* | --target=*) targets+=( "${opt#*=}" ) ;;
		--s | --sisyphus) branches+=("sisyphus") ;;
		--[cp][[:digit:]]*) branches+=(${opt#--}) ;;
		--branch=* | --repo=*) branches+=(${arg//,/ }) ;;
		--task=*) task="${opt#*=}" ;;
		--build-srpm-only | -bs) gear_hsh=("hsh" "--build-srpm-only") ;;
		--install-only) gear_hsh=("hsh-rebuild" "$opt") ;;
		--build-check | -bt | -bi) build_check=y ;;
		--no-cache) no_cache=1 ;;
		--ini*) initroot=only ;;
		--no-ini*) noinitroot=ci ;;
		--rpmi=*|--ci=*) pkgi+=(${arg//,/ }) ;;
		--no-beep) NOBEEP=y ;;
		--ci) ci=checkinstall ;;
		--ci-all) ci=all ;;
		--ci-command=*) ci_command="${opt#*=}" ;;
		--clean | --repo-clean) hsh_clean=y ;;
		--fresh) fresh=y ;;
		--date=*|--archive=*) archive_date=${opt#*=} ;;
		--components=*) components=${opt#*=} ;;
		--rsync) do_rsync=y ;;
		--verbose) set_rpmargs+=" --verbose" ;;
		--define=*) set_rpmargs+=" --define '$arg'" ;;
		--disable-lto | --no-lto) set_rpmargs+=" --define 'optflags_lto %nil'" ;;
		--enable*|--disable*|--with*) opt=${opt#--}; set_rpmargs+=" --${opt/[-=]/ }" ;;
		--kernel-latest=*) set_rpmargs+=" --define 'kernel_latest $arg'" ;;
		--kflavour=*) kflavour=${opt#*=} ;;
		--tree-ish=* | -t=*) commit=("-t" "${opt#*=}") ;;
		--ts=*) ts=${opt#*=} ;;
		--no-log) no_log=y ;;
		--wait-lock | --no-wait-lock) wait_lock="$opt" ;;
		--) break ;;
		-*) fatal "Unknown option: $opt" ;;
                *) set -- "$@" "$opt";;
        esac
done
unset opt arg
type -p ts >/dev/null ||
	ts() { awk -v t="${1-%T}" '{ print strftime(t), $0}; fflush()'; }

[ "${bb_ts-}" = pwd ] && ts="($(basename "$PWD"))"

task_to_branch() {
	curl -sSLf "https://git.altlinux.org/tasks/${1?}/task/repo"
}

if [ ! -v branches ]; then
	if [ -v branch ]; then
		: # From upper level bb run.
	elif [ -v task ]; then
		branch=$task
	else
		branch="sisyphus"
	fi
	branches=("$branch")
fi
for branch in "${branches[@]}"; do
	[ "$branch" = 's' ] && branch=sisyphus
	[[ $branch == +([[:digit:]]) ]] &&
		branch=$(task_to_branch "$branch") &&
		continue
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
	elif [[ $archive_date =~ ([[:digit:]]{4})([[:digit:]]{2})?([[:digit:]]{2})? ]]; then
		archive_date=${BASH_REMATCH[1]}/${BASH_REMATCH[2]:-01}/${BASH_REMATCH[3]:-01}
	else
		fatal "Unknwon date format $archive_date"
	fi
fi

mkdir -p "${TMPDIR:-/tmp}/hasher"

[ -v set_rpmargs ] && set_rpmargs=${set_rpmargs# }
log_config() {
	echo "+ branch=${branch-} target=${set_target-} date=${archive_date-} task=${task-}"
	if [ -v set_rpmargs ]; then echo "+ rpmargs=$set_rpmargs"; fi
}

pkg_install() {
	build_state="CI initroot"
	if [ -v fresh ]; then
		((${#pkgi[@]})) &&
			echo -e "\n:: CI ${branch-Sisyphus} packages one by one: ${pkgi[*]}"
		for pkg in "${pkgi[@]}"; do
			(echo; set -x; hsh $wait_lock --initroot ${no_cache:+--no-cache})
			build_state="CI install-one $pkg"
			echo
			cd /var/empty
			(set -x; hsh-install "$pkg")
		done
	else
		((${#pkgi[@]})) &&
			echo -e "\n:: CI ${branch-Sisyphus} packages all at once: ${pkgi[*]}"
		[ -n "${noinitroot-}" ] || (echo; set -x; hsh $wait_lock --initroot ${no_cache:+--no-cache})
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

repo_clean() (
	set +f
	mkdir -p ~/repo
	rm -rf -v ~/repo/*
)
[ -v hsh_clean ] && repo_clean

export branch set_target archive_date task components set_rpmargs do_rsync no_log
if [ -n "${initroot-}" ]; then
	log_config
	pkg_install
	exit
elif [ -v gear_hsh ]; then
	log_config
	(set -x; gear --hasher "${commit[@]}" -- "${gear_hsh[@]}" $wait_lock)
	pkg_install
	exit
fi

toplevel=$(git rev-parse --show-toplevel)
[ "$toplevel" -ef . ] || {
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

unset rebuild_prog
if [ -v build_check ]; then
	rebuild_prog=$(mktemp --suffix=.hsh)
	readonly rebuild_prog
	cat > "$rebuild_prog" <<-"EOF"
		#!/bin/bash -le
		set -x
		rpmi -i -- "$@"
		specfile=( "$HOME"/RPM/SPECS/*.spec )
		[ -f "$specfile" ]
		export -n target
		read -r SOURCE_DATE_EPOCH < "$HOME/in/SOURCE_DATE_EPOCH"
		export SOURCE_DATE_EPOCH
		time rpmbuild -bi --target="$target" "$specfile"
	EOF
	set -- "--rebuild-prog=$rebuild_prog"
fi

set -o pipefail
unset build_state
aterr() {
	local red=$'\e[1;31m' norm=$'\e[m'
	echo "${red}FAILED ($(basename "$PWD")) ${branch-} ${set_target-} state=${build_state-}${norm}"
}
trap '{ set +x; } 2>/dev/null; aterr' ERR
atexit() {
	[ -v rebuild_prog ] && rm -- "$rebuild_prog"
	[ -v NOBEEP ] || beep
}
trap '{ set +x; } 2>/dev/null; atexit' EXIT
sep=

for target in "${targets[@]}"; do
for branch in "${branches[@]}"; do
	[ "$branch" = 's' ] && branch=sisyphus
	if [[ $branch == +([[:digit:]]) ]]; then
		task=$branch
		branch=$(task_to_branch "$task")
	fi
	set_target=$target
	# Reexport, since we did unset inside of the loop.
	export branch set_target

	[ -v hsh_clean ] && repo_clean

	if [ -v no_log ]; then
		log=/dev/null
	else
		for log in log log1 .log build.log; do
			[[ -d "$log" ]] || break
		done
		L=.git/bb/log.$(date +%F_%H%M)
		[ "sisyphus" = "$branch" ] && unset branch || L+=".$branch"
		[ "$HOSTTYPE" = "$set_target" ] && unset set_target || L+=".$set_target"
		[ -e .git/BISECT_LOG ] && L+=".bisect"
		ln -sf "$L" -T "$log"
		unset L
	fi

	[ -v hsh_clean ] && repo_clean
	printf '%s' "$sep"
	build_state="gear-hsh"
	{
		[ -e .git/BISECT_LOG ] && (set -x; cat .git/BISECT_LOG)
		set -x
		git diff
		# shellcheck disable=SC2094
		git 'log' -1 --decorate
	} &> "$log"
	{
		{ log_config; } 2>/dev/null
		gear-hsh $wait_lock "${commit[@]}" "${@}"
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
				sed -En 's!^\S+ Wrote:\s/usr/src/RPM/RPMS/[[:graph:]/]+/(\S+-checkinstall|\S+-ci-.*debuginfo)-\S+\.rpm\s.*!\1!p'
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
