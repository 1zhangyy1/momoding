#!/bin/zsh
# Saves your Gitee access token for the release script, outside the repo, readable only by you.
# Run it yourself; the token is read without echo and never printed.
set -e
D="$HOME/.momoding-release"
mkdir -p "$D" && chmod 700 "$D"
print -n "Gitee 用户名: "; read -r user
print -n "Gitee 私人令牌（输入不显示）: "; read -rs token; print
[ -n "$user" ] && [ -n "$token" ] || { print "没有输入，未保存"; exit 1; }
printf "GITEE_USER=%s\nGITEE_TOKEN=%s\n" "$user" "$token" > "$D/gitee.properties"
chmod 600 "$D/gitee.properties"
unset token
print "已保存到 $D/gitee.properties"
