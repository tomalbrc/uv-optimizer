package de.tomalbrc;

import com.google.gson.JsonElement;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

public record Texture(Key key, Path path, JsonElement metadata, BufferedImage image) {}