#!/usr/bin/env bash
set -e

SECRET_FILE=".env.secrets"

if [ -f "$SECRET_FILE" ]; then
    echo "Secrets file '$SECRET_FILE' already exists. Refusing to overwrite."
    exit 1
fi

echo "Generating local secrets..."

DB_PASS=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
TOKEN_KEY=$(openssl rand -hex 32)
ENROLL_KEY=$(openssl rand -base64 18 | tr -dc 'a-z0-9' | head -c 24 | sed -E 's/(.{4})(?=.)/\1-/g')
ADMIN_PASS=$(openssl rand -base64 16 | tr -dc 'a-zA-Z0-9' | head -c 16)

cat << EOF > "$SECRET_FILE"
POSTGRES_PASSWORD=$DB_PASS
TOKEN_SIGNING_KEY=$TOKEN_KEY
ENROLLMENT_KEY=$ENROLL_KEY
ADMIN_PASSWORD=$ADMIN_PASS
EOF

echo "Secrets generated successfully in $SECRET_FILE."
echo "Your device enrollment key is: $ENROLL_KEY"
echo "Save this key; you will need it to enroll phones and watches."
