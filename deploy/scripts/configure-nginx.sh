#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$#" -ne 2 ]]; then
  echo "usage: configure-nginx.sh <config-dir> <letsencrypt-email>" >&2
  exit 2
fi

if [[ "$EUID" -ne 0 ]]; then
  echo "configure-nginx.sh must run with sudo." >&2
  exit 1
fi

config_dir="$1"
letsencrypt_email="$2"
domain="ieum.rktclgh.site"
site_path="/etc/nginx/sites-available/$domain"
site_link="/etc/nginx/sites-enabled/$domain"
http_config="$config_dir/$domain.http.conf"
tls_config="$config_dir/$domain.conf"
reload_hook="$config_dir/reload-nginx.sh"
certificate_dir="/etc/letsencrypt/live/$domain"
backup_path="$site_path.pre-cicd"
run_backup="$(mktemp)"
trap 'rm -f "$run_backup"' EXIT

for file in "$http_config" "$tls_config" "$reload_hook"; do
  [[ -s "$file" ]] || { echo "missing Nginx deployment file: $file" >&2; exit 1; }
done

if ! command -v nginx >/dev/null 2>&1 || ! command -v certbot >/dev/null 2>&1; then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y nginx certbot
fi

install -d -m 0755 /var/www/certbot /etc/nginx/sites-available /etc/nginx/sites-enabled
install -d -m 0755 /etc/letsencrypt/renewal-hooks/deploy
install -m 0755 "$reload_hook" /etc/letsencrypt/renewal-hooks/deploy/reload-nginx

had_original=false
if [[ -f "$site_path" ]]; then
  had_original=true
  cp -a "$site_path" "$run_backup"
  if [[ ! -e "$backup_path" ]]; then
    cp -a "$site_path" "$backup_path"
  fi
fi

restore_original() {
  local status="$?"
  trap - ERR
  if [[ "$had_original" == true && -s "$run_backup" ]]; then
    install -m 0644 "$run_backup" "$site_path"
    ln -sfn "$site_path" "$site_link"
  else
    rm -f "$site_link" "$site_path"
  fi
  nginx -t && systemctl reload nginx
  rm -f "$run_backup"
  exit "$status"
}
trap restore_original ERR

activate_config() {
  local source="$1"
  install -m 0644 "$source" "$site_path"
  ln -sfn "$site_path" "$site_link"
  rm -f /etc/nginx/sites-enabled/default
  nginx -t
  systemctl enable --now nginx
  systemctl reload nginx
}

if [[ ! -s "$certificate_dir/fullchain.pem" || ! -s "$certificate_dir/privkey.pem" ]]; then
  [[ "$letsencrypt_email" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]] || {
    echo "a valid Let's Encrypt email is required for initial certificate issuance" >&2
    exit 1
  }
  activate_config "$http_config"
  certbot certonly \
    --webroot \
    --webroot-path /var/www/certbot \
    --domain "$domain" \
    --email "$letsencrypt_email" \
    --agree-tos \
    --non-interactive \
    --keep-until-expiring
fi

activate_config "$tls_config"
systemctl enable --now certbot.timer
trap - ERR
rm -f "$run_backup"

echo "Nginx is serving https://$domain through 127.0.0.1:8080."
