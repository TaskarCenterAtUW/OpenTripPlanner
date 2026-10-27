package org.opentripplanner.routing.linking;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.street.model.edge.ExtensionRequestContext;
import org.opentripplanner.utils.tostring.ToStringBuilder;

public class LinkingContextRequest {

  private static final LinkingContextRequest DEFAULT = new LinkingContextRequest();

  private final GenericLocation from;
  private final GenericLocation to;
  private final List<GenericLocation> viaLocationsWithCoordinates;
  private final StreetMode accessMode;
  private final StreetMode egressMode;
  private final StreetMode directMode;
  private final StreetMode transferMode;
  private final List<ExtensionRequestContext> extensions;

  private LinkingContextRequest() {
    this.from = null;
    this.to = null;
    this.viaLocationsWithCoordinates = List.of();
    this.accessMode = StreetMode.NOT_SET;
    this.egressMode = StreetMode.NOT_SET;
    this.directMode = StreetMode.NOT_SET;
    this.transferMode = StreetMode.NOT_SET;
    this.extensions = List.of();
  }

  public LinkingContextRequest(LinkingContextRequestBuilder builder) {
    this.from = Objects.requireNonNull(builder.from());
    this.to = builder.to();
    this.viaLocationsWithCoordinates = builder.viaLocationsWithCoordinates();
    this.accessMode = builder.accessMode();
    this.egressMode = builder.egressMode();
    this.directMode = builder.directMode();
    this.transferMode = builder.transferMode();
    this.extensions = List.copyOf(builder.extensions());
  }

  public static LinkingContextRequestBuilder of() {
    return new LinkingContextRequestBuilder(DEFAULT);
  }

  public LinkingContextRequestBuilder copyOf() {
    return new LinkingContextRequestBuilder(this);
  }

  public GenericLocation from() {
    return from;
  }

  @Nullable
  public GenericLocation to() {
    return to;
  }

  public List<GenericLocation> viaLocationsWithCoordinates() {
    return viaLocationsWithCoordinates;
  }

  public StreetMode accessMode() {
    return accessMode;
  }

  public StreetMode egressMode() {
    return egressMode;
  }

  public StreetMode directMode() {
    return directMode;
  }

  public StreetMode transferMode() {
    return transferMode;
  }

  /**
   * What sandbox extensions need to know about the request the locations are linked for. See
   * {@link org.opentripplanner.routing.api.request.preference.SystemPreferences#extensions()}.
   */
  public List<ExtensionRequestContext> extensions() {
    return extensions;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    var other = (LinkingContextRequest) o;
    return (
      Objects.equals(from, other.from) &&
      Objects.equals(to, other.to) &&
      Objects.equals(viaLocationsWithCoordinates, other.viaLocationsWithCoordinates) &&
      accessMode == other.accessMode &&
      egressMode == other.egressMode &&
      directMode == other.directMode &&
      transferMode == other.transferMode &&
      extensions.equals(other.extensions)
    );
  }

  @Override
  public int hashCode() {
    return Objects.hash(
      from,
      to,
      viaLocationsWithCoordinates,
      accessMode,
      egressMode,
      directMode,
      transferMode,
      extensions
    );
  }

  @Override
  public String toString() {
    return ToStringBuilder.of(LinkingContextRequest.class)
      .addObj("from", from, DEFAULT.from)
      .addObj("to", to, DEFAULT.to)
      .addCol("viaLocationsWithCoordinates", viaLocationsWithCoordinates)
      .addEnum("accessMode", accessMode, DEFAULT.accessMode)
      .addEnum("egressMode", egressMode, DEFAULT.egressMode)
      .addEnum("directMode", directMode, DEFAULT.directMode)
      .addEnum("transferMode", transferMode, DEFAULT.transferMode)
      .addCol("extensions", extensions, DEFAULT.extensions)
      .toString();
  }
}
