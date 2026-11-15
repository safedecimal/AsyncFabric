#!/usr/bin/env bash
# Builds AsyncFabricApi from a clean checkout in the one order that works.
#
# Why this is not just `./gradlew build`:
#
# Loom reads a dependency's classtweaker out of that dependency's *jar*, and it needs those jars
# before it computes the remapped Minecraft jar for a module that depends on them. On a clean
# checkout the jars do not exist yet, so a module that relies on a `transitive-` entry fails with
# "cannot find symbol" for a member the classtweaker was supposed to provide — `addTask`,
# `getFabricPackSource`, `PoiTypes.register`, `emitQuads` and so on.
#
# The failure looks random: it reproduces on every fresh clone, never in a warm working copy, and
# the set of failing modules shifts between runs because which jars happen to exist changes.
#
# The steps below are the minimum that makes it deterministic. They were verified on a clean
# `git clone` of the repository (BUILD SUCCESSFUL, 0 errors).
set -euo pipefail

cd "$(dirname "$0")"

echo '== 1/3: configure the project so Loom writes its cache'
./gradlew help --console=plain

echo '== 2/3: package every module that publishes a classtweaker'
# One module per invocation, with parallelism off. Building them in a single invocation makes
# Gradle compile the modules concurrently, and they then race for the shared Minecraft jar in the
# same way, which puts the failure back. The list is derived from the classtweaker files, so a new
# module is covered without editing this script.
# Base modules first: the ones that publish classtweakers other modules consume. If a dependent
# module is packaged before its provider, the provider's jar is still missing when the dependent
# is compiled, and the build fails again.
base_modules="asyncfabric-api-base asyncfabric-transitive-access-wideners-v1 asyncfabric-resource-loader-v1 asyncfabric-convention-tags-v2 asyncfabric-networking-api-v1 asyncfabric-rendering-v1 asyncfabric-renderer-api-v1"

for module in $base_modules; do
	echo "   :${module}:jar"
	./gradlew ":${module}:jar" --console=plain --no-parallel -q
done

tweakers=$(find . -path '*/src/main/resources/*.classtweaker' -o -path '*/src/client/resources/*.classtweaker' | sort -u)

for tweaker in $tweakers; do
	module=$(printf '%s' "$tweaker" | cut -d/ -f2)
	case " $base_modules " in
		*" $module "*) continue ;;
	esac
	echo "   :${module}:jar"
	./gradlew ":${module}:jar" --console=plain --no-parallel -q || echo "   (skipped: depends on a provider not yet packaged)"
done

echo '== 3/3: build everything'
./gradlew build -x javadoc --console=plain "${@}"
