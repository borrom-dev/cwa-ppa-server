package app.coronawarn.datadonation.services.ppac.android.controller;

import app.coronawarn.datadonation.common.persistence.domain.OneTimePassword;
import app.coronawarn.datadonation.common.persistence.domain.metrics.ExposureRiskMetadata;
import app.coronawarn.datadonation.common.persistence.domain.ppac.android.Salt;
import app.coronawarn.datadonation.common.persistence.repository.ppac.android.SaltRepository;
import app.coronawarn.datadonation.common.persistence.service.OtpCreationResponse;
import app.coronawarn.datadonation.common.persistence.service.OtpService;
import app.coronawarn.datadonation.common.persistence.service.PpaDataStorageRequest;
import app.coronawarn.datadonation.common.protocols.internal.ppdd.EdusOtp.EDUSOneTimePassword;
import app.coronawarn.datadonation.common.protocols.internal.ppdd.EdusOtpRequestAndroid.EDUSOneTimePasswordRequestAndroid;
import app.coronawarn.datadonation.common.protocols.internal.ppdd.PPADataAndroid;
import app.coronawarn.datadonation.common.protocols.internal.ppdd.PPANewExposureWindow;
import app.coronawarn.datadonation.common.protocols.internal.ppdd.PpaDataRequestAndroid.PPADataRequestAndroid;
import app.coronawarn.datadonation.common.utils.TimeUtils;
import app.coronawarn.datadonation.services.ppac.android.attestation.signature.SignatureVerificationStrategy;
import app.coronawarn.datadonation.services.ppac.android.testdata.JwsGenerationUtil;
import app.coronawarn.datadonation.services.ppac.android.testdata.TestData;
import app.coronawarn.datadonation.services.ppac.android.testdata.TestData.CardinalityTestData;
import app.coronawarn.datadonation.services.ppac.config.PpacConfiguration;
import app.coronawarn.datadonation.services.ppac.config.TestBeanConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static app.coronawarn.datadonation.services.ppac.android.testdata.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.*;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestBeanConfig.class)
class AndroidControllerTest {

  private static final Salt EXPIRED_SALT =
      new Salt("abc", Instant.now().minus(5, ChronoUnit.HOURS).toEpochMilli());

  private static final Salt NOT_EXPIRED_SALT =
      new Salt("def", Instant.now().minus(1, ChronoUnit.HOURS).toEpochMilli());

  private static final String TEST_NONCE_VALUE = "AAAAAAAAAAAAAAAAAAAAAA==";

  @MockBean
  private SignatureVerificationStrategy signatureVerificationStrategy;

  @Autowired
  private RequestExecutor executor;

  @SpyBean
  private PpaDataRequestAndroidConverter androidStorageConverter;

  @Autowired
  private PpacConfiguration ppacConfiguration;

  @SpyBean
  private OtpService otpService;

  @Nested
  class MockedSignatureVerificationStrategy {

    @BeforeEach
    void setup() throws GeneralSecurityException {
      mockedSignatureSetup();
    }

    @Test
    @Disabled("Temporarily disabled until test refactoring commited")
    void checkResponseStatusForValidNonce() throws IOException {
      ppacConfiguration.getAndroid().setCertificateHostname("localhost");
      ResponseEntity<Void> actResponse = executor.executePost(buildPayloadWithValidNonce());
      assertThat(actResponse.getStatusCode()).isEqualTo(NO_CONTENT);
    }

    @Test
    @Disabled("Temporarily disabled until test refactoring commited")
    void checkResponseStatusForInvalidNonce() throws IOException {
      ppacConfiguration.getAndroid().setCertificateHostname("attest.google.com");
      ResponseEntity<Void> actResponse = executor.executePost(buildPayloadWithEmptyNonce());
      assertThat(actResponse.getStatusCode()).isEqualTo(FORBIDDEN);
      
      actResponse = executor.executePost(buildPayloadWithWrongNonce());
      assertThat(actResponse.getStatusCode()).isEqualTo(FORBIDDEN);
      ppacConfiguration.getAndroid().setCertificateHostname("localhost");
    }

    @Test
    void checkResponseStatusForInvalidApkPackageName() throws IOException {
      ppacConfiguration.getAndroid().setCertificateHostname("localhost");
      ppacConfiguration.getAndroid().setAllowedApkPackageNames(new String[]{"de.rki.coronawarnapp.wrong"});
      ResponseEntity<Void> actResponse = executor.executePost(buildPayloadWithValidNonce());
      assertThat(actResponse.getStatusCode()).isEqualTo(FORBIDDEN);
    }

    @Test
    void checkResponseStatusForApkCertificateDigestsNotAllowed() throws IOException {
      ppacConfiguration.getAndroid().setDisableNonceCheck(true);
      ppacConfiguration.getAndroid().setCertificateHostname("localhost");
      ppacConfiguration.getAndroid().setAllowedApkCertificateDigests(
          new String[]{"9VLvUGV0Gkx24etruEBYikvAtqSQ9iY6rYuKhG-wrong"});
      ResponseEntity<Void> actResponse = executor.executePost(buildPayloadWithValidNonce());
      ppacConfiguration.getAndroid().setDisableNonceCheck(false);
      
      assertThat(actResponse.getStatusCode()).isEqualTo(FORBIDDEN);
    }

    /**
     * Test sends data with unallowed APK Certificate Digests but the validation check flag for this
     * field is disabled.
     */
    @Test
    void checkResponseStatusForWrongApkCertitificateDigestsAndDisabledCheck() throws IOException {
      ppacConfiguration.getAndroid().setDisableApkCertificateDigestsCheck(true);
      ppacConfiguration.getAndroid().setDisableNonceCheck(true);
      ppacConfiguration.getAndroid().setCertificateHostname("localhost");
      ppacConfiguration.getAndroid().setAllowedApkCertificateDigests(
          new String[]{"9VLvUGV0Gkx24etruEBYikvAtqSQ9iY6rYuKhG-wrong"});
      ResponseEntity<Void> actResponse = executor.executePost(buildPayloadWithValidNonce());
      ppacConfiguration.getAndroid().setDisableApkCertificateDigestsCheck(false);
      ppacConfiguration.getAndroid().setDisableNonceCheck(false);
      
      assertThat(actResponse.getStatusCode()).isEqualTo(NO_CONTENT);
    }
    
    @Test
    @Disabled("Temporarily disabled until test refactoring commited")
    void checkResponseStatusForValidNonceAndMultipleCertificatesDigest() throws IOException {
      String[] certificatesDigest = new String("9VLvUGV0Gkx24etruEBYikvAtqSQ9iY6rYuKhG+xwKE=,"
          + "Dday+17d9vY5YtsnHu1+9QTHd9l3LUhEcqzweVOe5zk=,HxzwEJQbZi1DPcTxBoTbzWKljMDhfDEWV6no4/xylVk=")
          .split(",");

      ppacConfiguration.getAndroid().setCertificateHostname("localhost");
      ppacConfiguration.getAndroid().setAllowedApkCertificateDigests(certificatesDigest);
      ResponseEntity<Void> actResponse = executor.executePost(buildPayloadWithValidNonce());
      assertThat(actResponse.getStatusCode()).isEqualTo(NO_CONTENT);
    }

    @Test
    void checkResponseStatusForFailedAttestationTimestampValidation() throws IOException {
      ppacConfiguration.getAndroid().setCertificateHostname("localhost");
      ppacConfiguration.getAndroid().setAttestationValidity(-100);
      ResponseEntity<Void> actResponse = executor.executePost(buildPayloadWithValidNonce());
      assertThat(actResponse.getStatusCode()).isEqualTo(FORBIDDEN);
    }

    @Test
    void checkResponseStatusForInvalidHostname() throws IOException {
      ppacConfiguration.getAndroid().setCertificateHostname("attest.google.com");
      ppacConfiguration.getAndroid().setDisableNonceCheck(true);
      ResponseEntity<Void> actResponse = executor.executePost(buildPayload());
      ppacConfiguration.getAndroid().setDisableNonceCheck(false);
      ppacConfiguration.getAndroid().setCertificateHostname("localhost");
      
      assertThat(actResponse.getStatusCode()).isEqualTo(FORBIDDEN);
    }

    @Test
    void checkResponseStatusForMissingSalt() throws IOException {
      ResponseEntity<Void> actResponse = executor.executePost(buildPayloadWithMissingSalt());
      assertThat(actResponse.getStatusCode()).isEqualTo(BAD_REQUEST);
    }

    @Test
    void checkResponseStatusForExpiredSalt() throws IOException {
      ResponseEntity<Void> actResponse = executor.executePost(buildPayloadWithExpiredSalt());
      assertThat(actResponse.getStatusCode()).isEqualTo(FORBIDDEN);
    }

    @Test
    void checkResponseStatusForMissingJws() throws IOException {
      ResponseEntity<Void> actResponse = executor.executePost(buildPayloadWithMissingJws());
      assertThat(actResponse.getStatusCode()).isEqualTo(BAD_REQUEST);
    }

    @Test
    void checkResponseStatusForInvalidJwsParsing() throws IOException {
      ResponseEntity<Void> actResponse = executor.executePost(buildPayloadWithInvalidJwsParsing());
      assertThat(actResponse.getStatusCode()).isEqualTo(UNAUTHORIZED);
    }
    
    @Test
    void checkResponseStatusForInvalidSignature() throws IOException {
      ResponseEntity<Void> actResponse = executor.executePost(buildPayload());
      assertThat(actResponse.getStatusCode()).isEqualTo(UNAUTHORIZED);
    }
  }

  @Nested
  class MetricsValidation {

    @BeforeEach
    void setup() throws GeneralSecurityException {
      mockedSignatureSetup();
      ppacConfiguration.getAndroid().setCertificateHostname("localhost");
    }

    @ParameterizedTest
    @ValueSource(ints = {2,3})
    void checkResponseIsBadRequestForInvalidExposureRiskPayloadCardinality(Integer cardinality) throws IOException {
      PPADataRequestAndroid invalidPayload = buildPayloadWithInvalidExposureRiskMetricsCardinality(cardinality);
      PpaDataStorageRequest mockConverterResponse = TestData.getStorageRequestWithInvalidExposureRisk();
      checkResponseStatusIsBadRequestForInvalidPayload(invalidPayload, mockConverterResponse);
    }
    
    @ParameterizedTest
    @ValueSource(ints = {2,3,4,5})
    void checkResponseIsBadRequestForInvalidExposureWindowPayloadCardinality(Integer cardinality) throws IOException {
      ppacConfiguration.setMaxExposureWindowsToRejectSubmission(3);
      PPADataRequestAndroid invalidPayload = buildPayloadWithInvalidExposureWindowMetricsCardinality(cardinality);
      PpaDataStorageRequest mockConverterResponse = TestData.getStorageRequestWithInvalidExposureWindow();
      checkResponseStatusIsBadRequestForInvalidPayload(invalidPayload, mockConverterResponse);
    }

    @Test
    void checkResponseStatusIsBadRequestForInvalidTestResults() throws IOException {
      PPADataRequestAndroid invalidPayload = buildPayloadWithInvalidExposureWindowMetricsCardinality();
      PpaDataStorageRequest mockConverterResponse = TestData.getStorageRequestWithInvalidTestResults();
      checkResponseStatusIsBadRequestForInvalidPayload(invalidPayload, mockConverterResponse);
    }

    @Test
    void checkResponseStatusIsBadRequestForInvalidUserMetadata() throws IOException {
      PPADataRequestAndroid invalidPayload = buildPayloadWithInvalidExposureWindowMetricsCardinality();
      PpaDataStorageRequest mockConverterResponse = TestData.getStorageRequestWithInvalidUserMetadata();
      checkResponseStatusIsBadRequestForInvalidPayload(invalidPayload, mockConverterResponse);
    }

    @Test
    void checkResponseStatusIsBadRequestForInvalidClientMetadata() throws IOException {
      PPADataRequestAndroid invalidPayload = buildPayloadWithInvalidExposureWindowMetricsCardinality();
      PpaDataStorageRequest mockConverterResponse = TestData.getStorageRequestWithInvalidClientMetadata();
      checkResponseStatusIsBadRequestForInvalidPayload(invalidPayload, mockConverterResponse);
    }

    /**
     * @param invalidPayload  Invalid payload to test
     * @param ppaDataStorageRequest  This parameter is used for mocking the converter. When validations will be
     * performed directly at the web layer these tests will not use this mock anymore.
     */
    void checkResponseStatusIsBadRequestForInvalidPayload(PPADataRequestAndroid invalidPayload,
        PpaDataStorageRequest ppaDataStorageRequest) throws IOException {
      doReturn(ppaDataStorageRequest).when(androidStorageConverter)
          .convertToStorageRequest(eq(invalidPayload), eq(ppacConfiguration), any());
      ResponseEntity<Void> actResponse = executor.executePost(invalidPayload);
      assertThat(actResponse.getStatusCode()).isEqualTo(BAD_REQUEST);
    }

    @Test
    void checkResponseStatusIsOkForValidMetrics() throws IOException {
      ppacConfiguration.getAndroid().setDisableNonceCheck(true);
      ResponseEntity<Void> actResponse = executor.executePost(buildPayloadWithValidMetrics());
      ppacConfiguration.getAndroid().setDisableNonceCheck(false);
      assertThat(actResponse.getStatusCode()).isEqualTo(NO_CONTENT);
    }
  }

  @Nested
  class CreateOtpTests {

    @BeforeEach
    void setup() throws GeneralSecurityException {
      SaltRepository saltRepo = mock(SaltRepository.class);

      ppacConfiguration.getAndroid().setAllowedApkPackageNames(new String[]{"de.rki.coronawarnapp.test"});
      ppacConfiguration.getAndroid().setAllowedApkCertificateDigests(
          new String[]{"9VLvUGV0Gkx24etruEBYikvAtqSQ9iY6rYuKhG+xwKE="});
      ppacConfiguration.getAndroid().setAttestationValidity(7200);
      ppacConfiguration.getAndroid().setRequireCtsProfileMatch(false);
      ppacConfiguration.getAndroid().setRequireBasicIntegrity(false);
      ppacConfiguration.getAndroid().setRequireEvaluationTypeBasic(false);
      ppacConfiguration.getAndroid().setRequireEvaluationTypeHardwareBacked(false);

      when(saltRepo.findById(any())).then((ans) -> Optional.of(NOT_EXPIRED_SALT));
      when(signatureVerificationStrategy.verifySignature(any())).thenReturn(JwsGenerationUtil.getTestCertificate());
    }

    @Test
    void testOtpServiceIsCalled() throws IOException {
      ppacConfiguration.getAndroid().setCertificateHostname("localhost");
      String password = "8ff92541-792f-4223-9970-bf90bf53b1a1";
      ArgumentCaptor<OneTimePassword> otpCaptor = ArgumentCaptor.forClass(OneTimePassword.class);
      ArgumentCaptor<Integer> validityCaptor = ArgumentCaptor.forClass(Integer.class);

      ResponseEntity<OtpCreationResponse> actResponse =
          executor.executeOtpPost(buildOtpPayloadWithValidNonce(password));

      assertThat(actResponse.getStatusCode()).isEqualTo(OK);
      verify(otpService, times(1)).createOtp(otpCaptor.capture(), validityCaptor.capture());

      OneTimePassword cptOtp = otpCaptor.getValue();

      ZonedDateTime expectedExpirationTime = ZonedDateTime.now(ZoneOffset.UTC).plusHours(ppacConfiguration.getOtpValidityInHours());
      ZonedDateTime actualExpirationTime = TimeUtils.getZonedDateTimeFor(cptOtp.getExpirationTimestamp());

      assertThat(validityCaptor.getValue()).isEqualTo(ppacConfiguration.getOtpValidityInHours());
      assertThat(actualExpirationTime).isEqualToIgnoringSeconds(expectedExpirationTime);
      assertThat(cptOtp.getPassword()).isEqualTo(password);
      assertThat(cptOtp.getAndroidPpacBasicIntegrity()).isFalse();
      assertThat(cptOtp.getAndroidPpacCtsProfileMatch()).isFalse();
      assertThat(cptOtp.getAndroidPpacEvaluationTypeBasic()).isTrue();
      assertThat(cptOtp.getAndroidPpacEvaluationTypeHardwareBacked()).isFalse();
    }

    @Test
    void testResponseIs400WhenOtpIsInvalidUuid() throws IOException {
      ppacConfiguration.getAndroid().setCertificateHostname("localhost");
      String password = "invalid-uuid";
      ArgumentCaptor<OneTimePassword> otpCaptor = ArgumentCaptor.forClass(OneTimePassword.class);
      ArgumentCaptor<Integer> validityCaptor = ArgumentCaptor.forClass(Integer.class);

      ResponseEntity<OtpCreationResponse> actResponse = executor.executeOtpPost(buildOtpPayloadWithValidNonce(
          password));

      assertThat(actResponse.getStatusCode()).isEqualTo(BAD_REQUEST);
    }

    private EDUSOneTimePasswordRequestAndroid buildOtpPayloadWithValidNonce(String password) throws IOException {
      String jws = getJwsPayloadWithNonce("mFmhph4QE3GTKS0FRNw9UZCxXI7ue+7fGdqGENsfo4g=");
      return EDUSOneTimePasswordRequestAndroid.newBuilder()
          .setAuthentication(newAuthenticationObject(jws, NOT_EXPIRED_SALT.getSalt()))
          .setPayload(EDUSOneTimePassword.newBuilder().setOtp(password))
          .build();
    }
  }

  private PPADataRequestAndroid buildPayload() throws IOException {
    String jws = getJwsPayloadValues();
    return PPADataRequestAndroid.newBuilder()
        .setAuthentication(newAuthenticationObject(jws, NOT_EXPIRED_SALT.getSalt()))
        .setPayload(getValidAndroidDataPayload())
        .build();
  }

  private PPADataRequestAndroid buildPayloadWithMissingSalt() throws IOException {
    String jws = getJwsPayloadValues();
    return PPADataRequestAndroid.newBuilder()
        .setAuthentication(newAuthenticationObject(jws, ""))
        .setPayload(getValidAndroidDataPayload())
        .build();
  }

  private PPADataRequestAndroid buildPayloadWithExpiredSalt() throws IOException {
    String jws = getJwsPayloadValues();
    return PPADataRequestAndroid.newBuilder()
        .setAuthentication(newAuthenticationObject(jws, EXPIRED_SALT.getSalt()))
        .setPayload(getValidAndroidDataPayload())
        .build();
  }

  private PPADataRequestAndroid buildPayloadWithMissingJws() throws IOException {
    return PPADataRequestAndroid.newBuilder()
        .setAuthentication(newAuthenticationObject("", NOT_EXPIRED_SALT.getSalt()))
        .setPayload(getValidAndroidDataPayload())
        .build();
  }

  private PPADataRequestAndroid buildPayloadWithInvalidJwsParsing() throws IOException {
    return PPADataRequestAndroid.newBuilder()
        .setAuthentication(newAuthenticationObject("RANDOM STRING", NOT_EXPIRED_SALT.getSalt()))
        .setPayload(getValidAndroidDataPayload())
        .build();
  }

  private PPADataRequestAndroid buildPayloadWithValidNonce() throws IOException {
    String jws = getJwsPayloadWithNonce("eLJTzrT+rTJgxlADK+puUXf8FdODPugHhtRSVSd4jr4=");
    return PPADataRequestAndroid.newBuilder()
        .setAuthentication(newAuthenticationObject(jws, NOT_EXPIRED_SALT.getSalt()))
        .setPayload(getValidAndroidDataPayload())
        .build();
  }

  private PPADataRequestAndroid buildPayloadWithEmptyNonce() throws IOException {
    String jws = getJwsPayloadWithNonce("");
    return PPADataRequestAndroid.newBuilder()
        .setAuthentication(newAuthenticationObject(jws, NOT_EXPIRED_SALT.getSalt()))
        .setPayload(getValidAndroidDataPayload())
        .build();
  }
  
  private PPADataRequestAndroid buildPayloadWithWrongNonce() throws IOException {
    String jws = getJwsPayloadWithNonce(TEST_NONCE_VALUE);
    return PPADataRequestAndroid.newBuilder()
        .setAuthentication(newAuthenticationObject(jws, NOT_EXPIRED_SALT.getSalt()))
        .setPayload(getValidAndroidDataPayload())
        .build();
  }
  
  private PPADataRequestAndroid buildPayloadWithValidMetrics() throws IOException {
    String jws = getJwsPayloadWithNonce("SGxUVHS88vcQzy6X8jDrIGuGWNGgwaFbyYFBUwfJxeI=");
    return PPADataRequestAndroid.newBuilder()
        .setAuthentication(newAuthenticationObject(jws, NOT_EXPIRED_SALT.getSalt()))
        .setPayload(PPADataAndroid.newBuilder()
            .addAllExposureRiskMetadataSet(Set.of(TestData.getValidExposureRiskMetadata()))
            .addAllNewExposureWindows(Set.of(TestData.getValidExposureWindow()))
            .addAllTestResultMetadataSet(Set.of(TestData.getValidTestResultMetadata()))
            .addAllKeySubmissionMetadataSet(Set.of(TestData.getValidKeySubmissionMetadata()))
            .setClientMetadata(TestData.getValidClientMetadata())
            .setUserMetadata(TestData.getValidUserMetadata()))
        .build();
  }

  private PPADataRequestAndroid buildPayloadWithInvalidExposureRiskMetricsCardinality(
      Integer numberOfWindows) throws IOException {
    String jws = getJwsPayloadWithNonce("USpoTt6jaVdHkQcImJBx09BE5jC9ea5W/k7NNSgOaP8=");
    return CardinalityTestData.buildPayloadWithInvalidExposureRiskMetricsCardinality(jws,
        NOT_EXPIRED_SALT.getSalt(), numberOfWindows);
  }

  private PPADataRequestAndroid buildPayloadWithInvalidExposureWindowMetricsCardinality(
      Integer numberOfWindows) throws IOException {
    String jws = getJwsPayloadWithNonce("USpoTt6jaVdHkQcImJBx09BE5jC9ea5W/k7NNSgOaP8=");
    return CardinalityTestData.buildPayloadWithInvalidExposureWindowMetricsCardinality(jws,
        NOT_EXPIRED_SALT.getSalt(), numberOfWindows);
  }
  
  private void mockedSignatureSetup() throws GeneralSecurityException {
    SaltRepository saltRepo = mock(SaltRepository.class);

    ppacConfiguration.getAndroid().setAllowedApkPackageNames(new String[]{"de.rki.coronawarnapp.test"});
    ppacConfiguration.getAndroid().setAllowedApkCertificateDigests(
        new String[]{"9VLvUGV0Gkx24etruEBYikvAtqSQ9iY6rYuKhG+xwKE="});
    ppacConfiguration.getAndroid().setAttestationValidity(7200);
    ppacConfiguration.getAndroid().setRequireBasicIntegrity(false);
    ppacConfiguration.getAndroid().setRequireCtsProfileMatch(false);
    ppacConfiguration.getAndroid().setRequireEvaluationTypeHardwareBacked(false);
    ppacConfiguration.getAndroid().setRequireEvaluationTypeBasic(false);


    when(saltRepo.findById(any())).then((ans) -> Optional.of(NOT_EXPIRED_SALT));
    when(signatureVerificationStrategy.verifySignature(any())).thenReturn(JwsGenerationUtil.getTestCertificate());
  }
}