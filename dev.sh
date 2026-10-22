#!/bin/bash

set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

MAVEN_OPTS="--add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED --add-opens=java.desktop/java.beans=ALL-UNNAMED --add-modules=java.se --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-opens=java.management/sun.management=ALL-UNNAMED --add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED --add-opens=java.base/sun.nio.fs=ALL-UNNAMED"
export MAVEN_OPTS

HOTSWAP_AGENT_VERSION=2.0.3
ECJ_VERSION=3.38.0
MAVEN_REPOSITORY="${MAVEN_REPOSITORY:-$HOME/.m2/repository}"
AGENT_PID_FILE="$ROOT/target/sandbox/dev-agent.pid"

running_agent() {
	[ -f "$AGENT_PID_FILE" ] || return 1
	agent_pid=$(cat "$AGENT_PID_FILE")
	case "$agent_pid" in
		''|*[!0-9]*|0) return 1 ;;
	esac
	# The development classpath can make the command line very long.
	agent_command=$(ps -ww -p "$agent_pid" -o args=) || return 1
	case "$agent_command" in
		*"-Donedev.dev.root=$ROOT "*"io.onedev.agent.Agent"*) return 0 ;;
		*) return 1 ;;
	esac
}

stop_agent() {
	if ! running_agent; then
		rm -f "$AGENT_PID_FILE"
		echo "Development agent is not running."
		return
	fi

	echo "Stopping development agent (PID $agent_pid)..."
	kill "$agent_pid"
	for ((attempt = 0; attempt < 30; attempt++)); do
		if ! running_agent; then
			echo "Development agent stopped."
			return
		fi
		sleep 1
	done
	echo "Development agent did not stop within 30 seconds. Check the agent console." >&2
	return 1
}

run_maven() {
	mvn -Dmaven.repo.local="$MAVEN_REPOSITORY" "$@"
}

artifact_path() {
	group_path=$(echo "$1" | tr . /)
	echo "$MAVEN_REPOSITORY/$group_path/$2/$3/$2-$3.jar"
}

ensure_artifact() {
	artifact=$(artifact_path "$1" "$2" "$3")
	if [ ! -f "$artifact" ]; then
		echo "Downloading $1:$2:$3..." >&2
		run_maven -q -U dependency:get -Dartifact="$1:$2:$3:jar" -Dtransitive=false >&2
	fi
	if [ ! -f "$artifact" ]; then
		echo "Unable to find $1:$2:$3 in $MAVEN_REPOSITORY" >&2
		return 1
	fi
	echo "$artifact"
}

build_classpath() {
	deps_classpath_file=target/deps-classpath.txt
	if [ ! -s "$deps_classpath_file" ]; then
		run_maven dependency:build-classpath -Dmdep.outputFile=target/deps-classpath.txt || return 1
	fi
	classpath="$ROOT/target/classes:$(cat "$deps_classpath_file")"
}

compile_changed() {
	work_dir=$(mktemp -d "${TMPDIR:-/tmp}/onedev-agent-compile.XXXXXX")
	sources="$work_dir/sources"
	: > "$sources"

	if [ "$#" -gt 0 ]; then
		for source in "$@"; do
			case "$source" in
				*.java) ;;
				*) echo "Not a Java source file: $source" >&2; rm -rf "$work_dir"; return 1 ;;
			esac
			if [ ! -f "$source" ]; then
				echo "Java source file does not exist: $source" >&2
				rm -rf "$work_dir"
				return 1
			fi
			case "$source" in
				/*) printf '"%s"\n' "$source" ;;
				*) printf '"%s"\n' "$ROOT/${source#./}" ;;
			esac >> "$sources"
		done
	else
		find src/main/java -name '*.java' -type f | while read -r source; do
			relative=${source#src/main/java/}
			class="target/classes/${relative%.java}.class"
			if [ ! -f "$class" ] || [ "$source" -nt "$class" ]; then
				printf '"%s"\n' "$ROOT/$source"
			fi
		done > "$sources"
	fi

	if [ ! -s "$sources" ]; then
		echo "No changed Java source files to compile."
		rm -rf "$work_dir"
		return
	fi

	ecj=$(ensure_artifact org.eclipse.jdt ecj "$ECJ_VERSION") || {
		rm -rf "$work_dir"
		return 1
	}
	if [ -z "${classpath:-}" ]; then
		build_classpath || {
			rm -rf "$work_dir"
			return 1
		}
	fi

	mkdir -p target/classes
	count=$(wc -l < "$sources" | tr -d ' ')
	echo "Compiling $count changed file(s)..."
	java -jar "$ecj" -17 -encoding UTF-8 -g -parameters -proc:none -nowarn \
		-classpath "$classpath" -d target/classes "@$sources" || {
		rm -rf "$work_dir"
		return 1
	}
	rm -rf "$work_dir"
}

copy_changed_resources() {
	work_dir=$(mktemp -d "${TMPDIR:-/tmp}/onedev-agent-resources.XXXXXX")
	resources="$work_dir/resources"
	: > "$resources"

	find src/main -type f ! -name '.DS_Store' ! -name '.*.sw?' ! -name '*~' \( \
		\( -path 'src/main/java/*' ! -name '*.java' \) -o \
		-path 'src/main/resources/*' \
	\) | while read -r source; do
		case "$source" in
			src/main/resources/*) relative=${source#src/main/resources/} ;;
			src/main/java/*) relative=${source#src/main/java/} ;;
		esac
		target="target/classes/$relative"
		if [ ! -f "$target" ] || [ "$source" -nt "$target" ]; then
			echo "$source" >> "$resources"
		fi
	done

	if [ -s "$resources" ]; then
		# process-resources also refreshes the agent configuration in target/sandbox.
		if ! run_maven -q process-resources; then
			rm -rf "$work_dir"
			return 1
		fi
		while read -r source; do
			echo "Copied resource: $source"
		done < "$resources"
	fi
	rm -rf "$work_dir"
}

watch_and_build() {
	reference=$1
	while true; do
		sleep 1
		if find pom.xml src/main -type f \
			! -name '.DS_Store' ! -name '.*.sw?' ! -name '*~' \
			-newer "$reference" -print -quit | grep -q .; then
			detected=$(mktemp "${TMPDIR:-/tmp}/onedev-agent-change.XXXXXX")
			touch "$detected"
			echo >&2
			echo "Changes detected. Running incremental build..." >&2
			if ! build_project; then
				echo "Build failed. Waiting for further changes..." >&2
			fi
			touch -r "$detected" "$reference"
			rm -f "$detected"
		fi
	done
}

compile_project() {
	rm -f target/deps-classpath.txt
	run_maven compile "$@" || return 1
	if [ ! -d target/sandbox ]; then
		echo "Maven compile did not create target/sandbox" >&2
		return 1
	fi
	classpath=
	touch target/sandbox
}

restore_preserved_sandbox() {
	if [ -n "${preserved_sandbox:-}" ] && [ -d "$preserved_sandbox" ]; then
		mkdir -p "$ROOT/target"
		if [ -e "$ROOT/target/sandbox" ]; then
			echo "Unable to restore sandbox: destination already exists" >&2
			return 1
		fi
		mv "$preserved_sandbox" "$ROOT/target/sandbox" || return 1
		rmdir "$preservation_dir" || return 1
		preserved_sandbox=
		preservation_dir=
	fi
}

rebuild_project() {
	sandbox="$ROOT/target/sandbox"
	preservation_dir=
	preserved_sandbox=
	sandbox_preserved=false
	if [ -d "$sandbox" ]; then
		preservation_dir=$(mktemp -d "$ROOT/.onedev-sandbox.XXXXXX")
		preserved_sandbox="$preservation_dir/sandbox"
		trap restore_preserved_sandbox EXIT
		if ! mv "$sandbox" "$preserved_sandbox"; then
			trap - EXIT
			rmdir "$preservation_dir"
			return 1
		fi
		sandbox_preserved=true
	fi

	clean_status=0
	run_maven clean "$@" || clean_status=$?
	if ! restore_preserved_sandbox; then
		return 1
	fi
	if [ "$sandbox_preserved" = true ]; then
		trap - EXIT
	fi
	if [ "$clean_status" -ne 0 ]; then
		return "$clean_status"
	fi

	compile_project "$@"
}

build_project() {
	if [ ! -d target/sandbox ] || [ ! -d target/classes ]; then
		echo "Development build not found. Running: mvn compile"
		compile_project || return 1
		return
	fi

	if [ pom.xml -nt target/sandbox ]; then
		echo "pom.xml changed. Running: mvn compile"
		compile_project || return 1
		return
	fi

	copy_changed_resources || return 1
	compile_changed "$@" || return 1
	touch target/sandbox
}

usage() {
	echo "Usage: ./dev.sh <command> [<command>...] [options]"
	echo
	echo "Commands:"
	echo "  run      Build, start the dev agent, and rebuild automatically when files change"
	echo "  stop     Stop the agent started by run"
	echo "  build    Build with Maven when needed, otherwise compile changed files with ECJ"
	echo "  rebuild  Clean and build while preserving the development sandbox"
	echo "  test     Run tests with Maven"
	echo "  package  Package the agent with Maven"
	echo "  install  Install project artifacts with Maven"
	echo "  clean    Clean build outputs with Maven"
	echo
	echo "Run options (override environment variables and agent.properties):"
	echo "  --server-url <url>    OneDev server URL"
	echo "  --agent-token <token> Agent authentication token"
	echo
	echo "Commands may be combined, for example: ./dev.sh clean package"
}

is_command() {
	case "$1" in
		build|rebuild|clean|install|test|package|run|stop) return 0 ;;
		*) return 1 ;;
	esac
}

flush_maven_commands() {
	if [ "${#maven_commands[@]}" -gt 0 ]; then
		run_maven "${maven_commands[@]}" "${maven_arguments[@]}"
		maven_commands=()
		maven_arguments=()
	fi
}

if [ "$#" -eq 0 ]; then
	usage
	exit 1
fi

maven_commands=()
maven_arguments=()
run_requested=false
run_arguments=()
while [ "$#" -gt 0 ]; do
	command=$1
	shift
	case "$command" in
		clean|install|test|package)
			maven_commands+=("$command")
			while [ "$#" -gt 0 ] && ! is_command "$1"; do
				maven_arguments+=("$1")
				shift
			done
			;;
		build|rebuild)
			flush_maven_commands
			command_arguments=()
			while [ "$#" -gt 0 ] && ! is_command "$1"; do
				command_arguments+=("$1")
				shift
			done
			if [ "$command" = build ]; then
				build_project "${command_arguments[@]}"
			else
				rebuild_project "${command_arguments[@]}"
			fi
			;;
		stop)
			flush_maven_commands
			stop_agent
			;;
		run)
			flush_maven_commands
			run_requested=true
			run_arguments=("$@")
			break
			;;
		*)
			usage >&2
			exit 1
			;;
	esac
done
flush_maven_commands

if [ "$run_requested" != true ]; then
	exit
fi

set -- "${run_arguments[@]}"
while [ "$#" -gt 0 ]; do
	option=$1
	case "$option" in
		--server-url|--agent-token)
			if [ "$#" -lt 2 ]; then
				echo "Option $option requires a value." >&2
				exit 1
			fi
			value=$2
			shift 2
			;;
		--server-url=*|--agent-token=*)
			value=${option#*=}
			option=${option%%=*}
			shift
			;;
		*)
			echo "Unknown run option: $option" >&2
			usage >&2
			exit 1
			;;
	esac
	if [ -z "$value" ] || [[ "$value" == --* ]]; then
		echo "Option $option requires a value." >&2
		exit 1
	fi
	case "$option" in
		--server-url) export serverUrl="$value" ;;
		--agent-token) export agentToken="$value" ;;
	esac
done

if running_agent; then
	echo "Development agent is already running (PID $agent_pid). Use ./dev.sh stop first." >&2
	exit 1
fi
watch_reference=$(mktemp "${TMPDIR:-/tmp}/onedev-agent-watch.XXXXXX")
touch "$watch_reference"
trap 'rm -f "$watch_reference"' EXIT
build_project

if [ -z "${classpath:-}" ]; then
	build_classpath
fi
hotswap_agent=$(ensure_artifact org.hotswapagent hotswap-agent "$HOTSWAP_AGENT_VERSION")
java_options=("-javaagent:$hotswap_agent=autoHotswap=true")
if java -XX:+AllowEnhancedClassRedefinition -version >/dev/null 2>&1; then
	java_options=(-XX:+AllowEnhancedClassRedefinition "${java_options[@]}")
else
	echo "Warning: this JVM only supports hot loading method-body changes." >&2
	echo "Use a JetBrains Runtime with AllowEnhancedClassRedefinition for structural changes." >&2
fi

cleanup_run() {
	if [ -n "${watcher_pid:-}" ]; then
		kill "$watcher_pid" 2>/dev/null || true
	fi
	if [ -n "${java_pid:-}" ]; then
		kill "$java_pid" 2>/dev/null || true
		wait "$java_pid" 2>/dev/null || true
		if [ "$(cat "$AGENT_PID_FILE" 2>/dev/null)" = "$java_pid" ]; then
			rm -f "$AGENT_PID_FILE"
		fi
	fi
	if [ -n "${watcher_pid:-}" ]; then
		wait "$watcher_pid" 2>/dev/null || true
	fi
	rm -f "$watch_reference"
}
trap cleanup_run EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "HotswapAgent enabled. Watching source files for changes." >&2
watch_and_build "$watch_reference" &
watcher_pid=$!

java $MAVEN_OPTS "${java_options[@]}" "-Donedev.dev.root=$ROOT" -cp "$classpath" \
	io.onedev.agent.Agent <&0 &
java_pid=$!
echo "$java_pid" > "$AGENT_PID_FILE"
wait "$java_pid"
