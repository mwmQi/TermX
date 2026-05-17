#!/bin/bash
# setup-release.sh - Generate release keystore and set up GitHub secrets for auto-release
#
# Usage: ./setup-release.sh <github-username>/<repo-name>
# Example: ./setup-release.sh mwmQi/TermX

set -e

REPO="${1:?Usage: $0 <github-username>/<repo-name>}"
KEYSTORE_FILE="release-keystore.jks"
KEYSTORE_ALIAS="termx-release"

echo "=== TermX Release Setup ==="
echo ""

# Generate keystore if it doesn't exist
if [ ! -f "$KEYSTORE_FILE" ]; then
    echo "Generating release keystore..."
    read -sp "Enter keystore password (min 6 chars): " STORE_PASS
    echo ""
    read -sp "Confirm keystore password: " STORE_PASS_CONFIRM
    echo ""

    if [ "$STORE_PASS" != "$STORE_PASS_CONFIRM" ]; then
        echo "Passwords do not match!"
        exit 1
    fi

    if [ ${#STORE_PASS} -lt 6 ]; then
        echo "Password must be at least 6 characters!"
        exit 1
    fi

    keytool -genkeypair -v \
        -keystore "$KEYSTORE_FILE" \
        -alias "$KEYSTORE_ALIAS" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass "$STORE_PASS" \
        -keypass "$STORE_PASS" \
        -dname "CN=TermX, OU=TermX, O=TermX, L=Unknown, S=Unknown, C=US"

    echo "Keystore generated: $KEYSTORE_FILE"
else
    echo "Using existing keystore: $KEYSTORE_FILE"
    read -sp "Enter keystore password: " STORE_PASS
    echo ""
fi

# Encode keystore to base64
KEYSTORE_BASE64=$(base64 -w 0 "$KEYSTORE_FILE")

echo ""
echo "=== GitHub Secrets to Configure ==="
echo ""
echo "Go to: https://github.com/$REPO/settings/secrets/actions"
echo ""
echo "Add these secrets:"
echo ""
echo "1. RELEASE_KEYSTORE_BASE64"
echo "   Value: (the base64 encoded keystore below)"
echo ""
echo "$KEYSTORE_BASE64" | head -c 100
echo "..."
echo ""
echo "2. RELEASE_KEYSTORE_PASSWORD"
echo "   Value: $STORE_PASS"
echo ""
echo "3. RELEASE_KEY_PASSWORD"
echo "   Value: $STORE_PASS"
echo ""

# Optionally set secrets via GitHub CLI if available
if command -v gh &> /dev/null; then
    echo ""
    read -p "Set secrets automatically via GitHub CLI? (y/n): " SET_SECRETS
    if [ "$SET_SECRETS" = "y" ] || [ "$SET_SECRETS" = "Y" ]; then
        echo "Setting GitHub secrets..."
        gh secret set RELEASE_KEYSTORE_BASE64 --body "$KEYSTORE_BASE64" --repo "$REPO"
        gh secret set RELEASE_KEYSTORE_PASSWORD --body "$STORE_PASS" --repo "$REPO"
        gh secret set RELEASE_KEY_PASSWORD --body "$STORE_PASS" --repo "$REPO"
        echo "Secrets set successfully!"
    fi
fi

echo ""
echo "=== Next Steps ==="
echo "1. Push the workflow file: git add .github/workflows/build-release.yml && git commit -m 'Add auto-release workflow'"
echo "2. Create a tag to trigger release: git tag v3.0.1 && git push origin v3.0.1"
echo "3. Or trigger manually from GitHub Actions tab"
echo ""
