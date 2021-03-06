package cz.smarteon.loxone.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.jetbrains.annotations.NotNull;

import cz.smarteon.loxone.LoxoneUuid;

/**
 * Base class for all the rooms in loxone application
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Room {

    @JsonProperty(value = "uuid", required = true)
    private LoxoneUuid uuid;

    @JsonProperty(value = "name", required = true)
    private String name;

    @JsonProperty(value = "image", required = true)
    private String image;

    @JsonProperty(value = "defaultRating", required = true)
    private int defaultRating;

    @JsonProperty(value = "isFavorite", required = true)
    private boolean isFavorite;

    @JsonProperty(value = "type", required = true)
    private int type;

    /**
     * UUID of this room, should be unique
     * @return room UUID
     */
    @NotNull
    public LoxoneUuid getUuid() {
        return uuid;
    }

    /**
     * Room name - usually localized, non unique
     * @return room name
     */
    @NotNull
    public String getName() {
        return name;
    }

    /**
     * Room image
     * @return room image
     */
    @NotNull
    public String getImage() {
        return image;
    }

    /**
     * Room defaultRating
     * @return room defaultRating
     */
    @NotNull
    public int getDefaultRating() {
        return defaultRating;
    }

    /**
     * Room isFavorite
     * @return room isFavorite
     */
    @NotNull
    public boolean getIsFavorite() {
        return isFavorite;
    }

    /**
     * Room type
     * @return room type
     */
    @NotNull
    public int getType() {
        return type;
    }

}
