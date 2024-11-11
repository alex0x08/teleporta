rem(){ :;};rem '
@goto b
';echo -n "a";

if [ -z "$BASH" ]; then 
echo -n "b"
exec bash "$0" "$@"
exit
fi

# this variable will contain full path to JRE binary
_JRE=

exit_error() {
    echo "Error found during bootstrap: ${1}"
    pause; exit 1
}

# Return the canonicalized path (works on OS-X like 'readlink -f' on Linux); . is $PWD
function get_realpath {
    [ "." = "${1}" ] && n=${PWD} || n=${1}; while nn=$( readlink -n "$n" ); do n=$nn; done; echo "$n"
}

try_download_java() {
echo -n "c"

UNPACKED_JRE=~/.jre/jre
if [[ -f "$UNPACKED_JRE/bin/java" ]]; then
    echo -n "d"
    _JRE="$UNPACKED_JRE/bin/java"
    return 0
fi

# Detect the platform (similar to $OSTYPE)
OS="$(uname)"
ARCH="$(uname -m)"
# select correct path segments based on CPU architecture and OS
case $ARCH in
   'x86_64')
     ARCH='x64'
     ;;
    'i686')
     ARCH='i586'
     ;;
    *)
    exit_error "Unsupported for automatic download"
     ;;
esac

case $OS in
  'Linux')
    OS='linux'
    ;;
  *)
    exit_error "Unsupported for automatic download"
     ;;
esac

echo -n "e"
#echo "3.2 Downloading for OS: $OS and arch: $ARCH"
URL="https://nexus.nuiton.org/nexus/content/repositories/jvm/com/oracle/jre/1.8.121/jre-1.8.121-$OS-$ARCH.zip"
#echo "Full url: $URL"

CODE=$(curl -L -w '%{http_code}' -o /tmp/jre.zip -C - $URL)
if [[ "$CODE" =~ ^2 ]]; then
    # Server returned 2xx response
    mkdir -p ~/.jre
    unzip /tmp/jre.zip -d ~/.jre/
    _JRE="$UNPACKED_JRE/bin/java"
    return 0
elif [[ "$CODE" = 404 ]]; then
    exit_error "Unable to download JRE from $URL"
else
    exit_error "Error downloading JRE: server returned HTTP code $CODE"
fi
}

# returns the JDK version.
# 8 for 1.8.0_nn, 9 for 9-ea etc, and "no_java" for undetected
jdk_version() {
  local result
  local java_cmd
  if [[ -n $(type -p java) ]]
  then
    java_cmd=java
  elif [[ (-n "$JAVA_HOME") && (-x "$JAVA_HOME/bin/java") ]]
  then
    java_cmd="$JAVA_HOME/bin/java"
  fi
  local IFS=$'\n'
  # remove \r for Cygwin
  local lines
  lines=$("$java_cmd" -Xms32M -Xmx32M -version 2>&1 | tr '\r' '\n')
  if [[ -z $java_cmd ]]
  then
    result=no_java
  else
    for line in $lines; do
      if [[ (-z $result) && ($line = *"version \""*) ]]
      then
        local ver
        ver=$(echo $line | sed -e 's/.*version "\(.*\)"\(.*\)/\1/; 1q')
        # on macOS, sed doesn't support '?'
        if [[ $ver = "1."* ]]
        then
          result=$(echo $ver | sed -e 's/1\.\([0-9]*\)\(.*\)/\1/; 1q')
        else
          result=$(echo $ver | sed -e 's/\([0-9]*\)\(.*\)/\1/; 1q')
        fi
      fi
    done
  fi
  echo "$result"
}

echo -n "f"
if type -p java >/dev/null; then
    echo -n "g"
    _JRE=java
elif [[ -n $JAVA_HOME ]] && [[ -x "$JAVA_HOME/bin/java" ]];  then
    echo -n "h"
    _JRE="$JAVA_HOME/bin/java"
else
    echo -n "i"
fi

v="$(jdk_version)"
#echo "2. Detected Java version: $v"
if [[ $v -lt 8 ]]
then
    #echo "2.1 Found unsupported version: $v"
    try_download_java
    #echo "2.2 Using JRE: $_JRE"
fi

self=$(get_realpath "$0")
$_JRE -jar "$self" "$@"
exit
