#!/bin/bash -efu

nproc=$(nproc)

export src=$1         # source config to test all options
export base=$(mktemp) # base config (source after olddefconfig) to compare to

cp $src $base
make olddefconfig KCONFIG_CONFIG=$base >/dev/null 2>&1
chmod a-w $base
rm $base.old

> .config-bad
> .config-good

# export CC="gcc-11" LD="ld" SRCARCH="x86" objtree="." srctree="."
# export PAHOLE="pahole" PAHOLE_FLAGS="--btf_gen_floats"
# export CC_VERSION_TEXT="gcc-11 (GCC) 11.2.1 20211202 (ALT Sisyphus 11.2.1-alt2)"
# export KERNELVERSION="5.16.17-un-def-alt1"
# export ARCH="x86"

test_option() {
	local c=$1
	local prep=/tmp/.config.$$

	# Exclude this option
	grep -F -x -v "$c" $src > $prep
	if diff -q $src $prep >/dev/null; then
		echo "No config defference for: $c"
		exit 1
	fi
	# Update .config with default values
	make olddefconfig KCONFIG_CONFIG=$prep >/dev/null 2>&1

	if diff -u $prep $base >/dev/null; then
		echo "  -- Unaffecting option $c"
		echo "$c" >> .config-bad
	else
		echo "  Relevant option: $c"
		echo "$c" >> .config-good
	fi
	rm $prep $prep.old
}

export -f test_option
# declare -i cnt=0 ofcnt=$(wc -l < $src)
while read c; do
	# cnt+=1
	# echo
	# echo "$cnt of $ofcnt: $c"
	if [[ $c =~ ^CONFIG_.* ]]; then
		echo "$c"
	elif  [[ $c =~ ^#.CONFIG_.*is.not.set ]]; then
		echo "$c"
	elif  [[ $c =~ ^#.* ]] || [[ $c =~ ^$ ]]; then
		:
	else
		echo "Invalid option: $c" 2>&1
		exit 1
	fi
done < $src \
| xargs -P$nproc -n1 -d $'\n' bash -c 'test_option "$@"' _

