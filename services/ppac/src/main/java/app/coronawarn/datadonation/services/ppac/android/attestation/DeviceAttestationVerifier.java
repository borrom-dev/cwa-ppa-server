package app.coronawarn.datadonation.services.ppac.android.attestation;

import app.coronawarn.datadonation.common.persistence.domain.android.Salt;
import app.coronawarn.datadonation.common.persistence.repository.android.SaltRepository;
import app.coronawarn.datadonation.common.protocols.internal.ppdd.PpacAndroid.PPACAndroid;
import app.coronawarn.datadonation.services.ppac.android.attestation.errors.ApkCertificateDigestsNotAllowed;
import app.coronawarn.datadonation.services.ppac.android.attestation.errors.ApkPackageNameNotAllowed;
import app.coronawarn.datadonation.services.ppac.android.attestation.errors.FailedAttestationHostnameValidation;
import app.coronawarn.datadonation.services.ppac.android.attestation.errors.FailedAttestationTimestampValidation;
import app.coronawarn.datadonation.services.ppac.android.attestation.errors.FailedJwsParsing;
import app.coronawarn.datadonation.services.ppac.android.attestation.errors.FailedSignatureVerification;
import app.coronawarn.datadonation.services.ppac.android.attestation.errors.MissingMandatoryAuthenticationFields;
import app.coronawarn.datadonation.services.ppac.android.attestation.errors.NonceCouldNotBeVerified;
import app.coronawarn.datadonation.services.ppac.android.attestation.errors.SaltNotValidAnymore;
import app.coronawarn.datadonation.services.ppac.config.PpacConfiguration;
import app.coronawarn.datadonation.services.ppac.utils.TimeUtils;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.common.base.Strings;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import javax.net.ssl.SSLException;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;

/**
 * For security purposes, each Android mobile device that participates in data donation gathering will
 * send an attestation statement (JWS) that helps with ensuring the client is running on a genuine
 * Android device. After assessing the device integrity, its OS issues the attestation statement
 * which must be checked by the data donation server before storing any metrics data. This class is used
 * to perform this validation.
 * 
 * @see <a href="https://developer.ppac.android.com/training/safetynet/attestation">SafetyNet API</a>
 * @see <a href=
 *      "https://github.com/googlesamples/android-play-safetynet/tree/e291afcacf6e25809cc666cc79711a9438a9b4a6/server">Sample
 *      verification</a>
 */
public class DeviceAttestationVerifier {

  private DefaultHostnameVerifier hostnameVerifier;
  private PpacConfiguration appParameters;
  private SaltRepository saltRepository;
  private SignatureVerificationStrategy signatureVerificationStrategy;

  /**
   * Constructs a verifier instance.
   */
  public DeviceAttestationVerifier(DefaultHostnameVerifier hostnameVerifier,
      PpacConfiguration appParameters, SaltRepository saltRepository, 
      SignatureVerificationStrategy signatureVerificationStrategy) {
    this.hostnameVerifier = hostnameVerifier;
    this.appParameters = appParameters;
    this.saltRepository = saltRepository;
    this.signatureVerificationStrategy = signatureVerificationStrategy;
  }

  /**
   * Perform several validations on the given signed attestation statement. In case of validation
   * problems specific runtime exceptions are thrown.
   * 
   * @throws MissingMandatoryAuthenticationFields - in case of fields which are expected are null
   * @throws FailedJwsParsing - in case of unparsable jws format
   * @throws FailedAttestationTimestampValidation - in case the timestamp in the JWS payload is
   *         expired
   * @throws FailedSignatureVerification - in case the signature can not be verified / trusted
   * @throws ApkPackageNameNotAllowed - in case contained apk package name is not part of the
   *         globally configured apk allowed list
   */
  public void validate(PPACAndroid authAndroid, NonceCalculator nonceCalculator) {
    validateSalt(authAndroid.getSalt());
    validateJws(authAndroid.getSafetyNetJws(), authAndroid.getSalt(), nonceCalculator);
  }

  private void validateSalt(String saltString) {
    if (Strings.isNullOrEmpty(saltString)) {
      throw new MissingMandatoryAuthenticationFields("Empty salt received");
    }
    saltRepository.findById(saltString).ifPresentOrElse(existingSalt -> {
      validateSaltCreationDate(existingSalt);
    }, () -> saltRepository.persist(saltString, Instant.now().toEpochMilli()));;
  }

  private void validateSaltCreationDate(Salt existingSalt) {
    Integer attestationValidity = appParameters.getAndroid().getAttestationValidity();
    Instant present = Instant.now();
    Instant lowerLimit = present.minusSeconds(attestationValidity);
    Instant saltCreationDate = Instant.ofEpochMilli(existingSalt.getCreatedAt());
    if (!saltCreationDate.isAfter(lowerLimit)) {
      throw new SaltNotValidAnymore(existingSalt);
    }
  }

  private void validateJws(String safetyNetJwsResult, String salt, NonceCalculator nonceCalculator) {
    if (Strings.isNullOrEmpty(safetyNetJwsResult)) {
      throw new MissingMandatoryAuthenticationFields("No JWS field received");
    }
    JsonWebSignature jws = parseJws(safetyNetJwsResult);
    validateSignature(jws);
    validatePayload(jws, salt, nonceCalculator);    
  }

  private void validatePayload(JsonWebSignature jws, String salt, NonceCalculator nonceCalculator) {
    AttestationStatement stmt = (AttestationStatement) jws.getPayload();
    validateNonce(salt, stmt.getNonce(), nonceCalculator);
    validateTimestamp(stmt.getTimestampMs());
    validateApkPackageName(stmt.getApkPackageName());
    validateApkCertificateDigestSha256(stmt.getEncodedApkCertificateDigestSha256());
  }

  private void validateNonce(String salt, String receivedNonce, NonceCalculator nonceCalculator) {
    if (Strings.isNullOrEmpty(receivedNonce)) {
      throw new MissingMandatoryAuthenticationFields("Nonce has not been received");
    }
    String recalculatedNonce = nonceCalculator.calculate(salt);
    if (!receivedNonce.contentEquals(recalculatedNonce)) {
      throw new NonceCouldNotBeVerified("Recalculated nonce " + recalculatedNonce
          + " does not match the received nonce " + receivedNonce);
    }
  }

  private void validateApkCertificateDigestSha256(String[] encodedApkCertDigests) {
    String[] allowedApkCertificateDigests =
        appParameters.getAndroid().getAllowedApkCertificateDigests();
  
    if (!(encodedApkCertDigests.length == 1
        && Arrays.asList(allowedApkCertificateDigests).contains(encodedApkCertDigests[0]))) {
      throw new ApkCertificateDigestsNotAllowed();
    }
  }

  private void validateApkPackageName(String apkPackageName) {
    String[] allowedApkPackageNames = appParameters.getAndroid().getAllowedApkPackageNames();
    if (!Arrays.asList(allowedApkPackageNames).contains(apkPackageName)) {
      throw new ApkPackageNameNotAllowed(apkPackageName);
    }
  }

  private void validateTimestamp(long timestampMs) {
    Integer attestationValidity = appParameters.getAndroid().getAttestationValidity();
    Instant present = Instant.now();
    Instant upperLimit = present.plusSeconds(attestationValidity);
    Instant lowerLimit = present.minusSeconds(attestationValidity);
    if (!TimeUtils.isInRange(timestampMs, lowerLimit, upperLimit)) {
      throw new FailedAttestationTimestampValidation();
    }
  }

  private void validateSignature(JsonWebSignature jws) {
    X509Certificate signatureCertificate = parseSignatureCertificate(jws);
    verifyHostname(appParameters.getAndroid().getCertificateHostname(), signatureCertificate);
  }

  /**
   * Use the underlying strategy to verify the JWS certificate chain and return the leaf in 
   * case valid.
   * @see SignatureVerificationStrategy#verifySignature(JsonWebSignature)
   */
  private X509Certificate parseSignatureCertificate(JsonWebSignature jws) {
    try {
      X509Certificate cert = signatureVerificationStrategy.verifySignature(jws);
      if (cert == null) {
        throw new FailedSignatureVerification(
            "Certificate missing - Error during cryptographic verification of the JWS signature: "
                + Arrays.toString(jws.getSignatureBytes()));
      }
      return cert;
    } catch (GeneralSecurityException e) {
      throw new FailedSignatureVerification(
          "Error during cryptographic verification of the JWS signature: "
              + Arrays.toString(jws.getSignatureBytes()), e);
    }
  }

  private JsonWebSignature parseJws(String signedAttestationStatment) {
    try {
      return JsonWebSignature.parser(GsonFactory.getDefaultInstance())
          .setPayloadClass(AttestationStatement.class).parse(signedAttestationStatment);
    } catch (Exception e) {
      throw new FailedJwsParsing(e);
    }
  }

  /**
   * Verifies that the certificate matches the specified hostname. Uses the
   * {@link DefaultHostnameVerifier} from the Apache HttpClient library to confirm that the hostname
   * matches the certificate.
   */
  private void verifyHostname(String hostname, X509Certificate leafCert) {
    try {
      hostnameVerifier.verify(hostname, leafCert);
    } catch (SSLException e) {
      throw new FailedAttestationHostnameValidation(
          "Hostname verification failed for attestation certificate.", e);
    }
  }
}