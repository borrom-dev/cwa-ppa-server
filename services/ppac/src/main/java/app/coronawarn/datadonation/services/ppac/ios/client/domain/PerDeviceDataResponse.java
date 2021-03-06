package app.coronawarn.datadonation.services.ppac.ios.client.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;

public class PerDeviceDataResponse {

  Boolean bit0;
  Boolean bit1;
  @JsonProperty("last_update_time")
  String lastUpdated; // YYYY-MM

  public PerDeviceDataResponse() {
    // empty constructor
  }

  /**
   * Create new instance of per-device data.
   *
   * @param bit0        first of a total of 2 bits.
   * @param bit1        second of a total of 2 bits.
   * @param lastUpdated when this per-device data was updated the last time.
   */
  public PerDeviceDataResponse(boolean bit0, boolean bit1, String lastUpdated) {
    this.bit0 = bit0;
    this.bit1 = bit1;
    this.lastUpdated = lastUpdated;
  }

  public boolean isBit0() {
    return bit0;
  }

  public void setBit0(boolean bit0) {
    this.bit0 = bit0;
  }

  public boolean isBit1() {
    return bit1;
  }

  public void setBit1(boolean bit1) {
    this.bit1 = bit1;
  }

  @JsonIgnore
  public Optional<String> getLastUpdated() {
    return Optional.ofNullable(lastUpdated);
  }

  public void setLastUpdated(String lastUpdated) {
    this.lastUpdated = lastUpdated;
  }
}
