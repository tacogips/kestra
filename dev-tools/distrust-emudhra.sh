#!/usr/bin/env sh
set -eu

# Remove both the direct and cross-signing roots identified at https://ops.java/emudhra.
sed -i \
    -e 's|^mozilla/emSign_ECC_Root_CA_-_G3.crt$|!&|' \
    -e 's|^mozilla/Comodo_AAA_Services_root.crt$|!&|' \
    /etc/ca-certificates.conf
update-ca-certificates --fresh

# Match root fingerprints because JDK vendors use different aliases for the same certificates.
aliases=$(mktemp)
trap 'rm -f "$aliases"' EXIT HUP INT TERM
keytool -list -v -cacerts -storepass changeit -J-Duser.language=en -J-Duser.country=US > "$aliases"
awk '
    /^Alias name: / { alias = substr($0, 13) }
    /SHA256:/ {
        fingerprint = $2
        gsub(":", "", fingerprint)
        if (fingerprint == "86A1ECBA089C4A8D3BBE2734C612BA341D813E043CF9E8A862CD5C57A36BBE6B" ||
            fingerprint == "D7A7A0FB5D7E2731D771E9484EBCDEF71D5F0C3E0A2948782BC83EE0EA699EF4" ||
            fingerprint == "7DD78D5F4F13459A83DFF9ABBBA62EDBAF6F2D102BF257FD712F4D9F2746ED8D" ||
            fingerprint == "F2EDAAFF3D9B701FB4D14C9F285F8C0BAD90FC3BCC1FA07D47F4718EDABA34A5") print alias
    }
' "$aliases" | while IFS= read -r alias; do
    keytool -delete -cacerts -storepass changeit -alias "$alias"
done
