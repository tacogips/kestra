import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Verifies the container's default Java trust store and optional public incident test chains. */
public final class VerifyEmudhraTrust {
    private static final Set<String> UNTRUSTED_ROOTS = Set.of(
        "86A1ECBA089C4A8D3BBE2734C612BA341D813E043CF9E8A862CD5C57A36BBE6B",
        "D7A7A0FB5D7E2731D771E9484EBCDEF71D5F0C3E0A2948782BC83EE0EA699EF4",
        "7DD78D5F4F13459A83DFF9ABBBA62EDBAF6F2D102BF257FD712F4D9F2746ED8D",
        "F2EDAAFF3D9B701FB4D14C9F285F8C0BAD90FC3BCC1FA07D47F4718EDABA34A5"
    );

    private VerifyEmudhraTrust() {
    }

    /** Checks trusted roots, then rejects every PEM chain in the optional directory argument. */
    public static void main(String[] args) throws java.io.IOException, java.security.GeneralSecurityException {
        KeyStore trustStore = KeyStore.getInstance(
            Path.of(System.getProperty("java.home"), "lib/security/cacerts").toFile(),
            "changeit".toCharArray()
        );
        if (trustStore.size() < 50) {
            throw new AssertionError("The trust store must retain unrelated public roots.");
        }
        for (String alias : java.util.Collections.list(trustStore.aliases())) {
            String fingerprint = HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(trustStore.getCertificate(alias).getEncoded())
            );
            if (UNTRUSTED_ROOTS.contains(fingerprint)) {
                throw new AssertionError("An affected root is still trusted: %s".formatted(alias));
            }
        }
        if (args.length > 0) {
            try (var paths = Files.list(Path.of(args[0]))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".pem")).toList()) {
                    List<? extends java.security.cert.Certificate> chain;
                    try (InputStream input = Files.newInputStream(path)) {
                        chain = List.copyOf(CertificateFactory.getInstance("X.509").generateCertificates(input));
                    }
                    X509CertSelector target = new X509CertSelector();
                    target.setCertificate((X509Certificate) chain.getFirst());
                    PKIXBuilderParameters parameters = new PKIXBuilderParameters(trustStore, target);
                    parameters.setRevocationEnabled(false);
                    parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(chain)));
                    try {
                        CertPathBuilder.getInstance("PKIX").build(parameters);
                        throw new AssertionError("A compromised chain is still trusted: %s".formatted(path));
                    } catch (CertPathBuilderException expected) {
                        System.out.println("Rejected " + path.getFileName());
                    }
                }
            }
        }
        System.out.println("Java trust store verification passed.");
    }
}
