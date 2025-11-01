package org.example;

import com.google.gson.JsonElement;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

public record Texture(Path path, JsonElement metadata, BufferedImage image) {}