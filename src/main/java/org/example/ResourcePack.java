package org.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

public class ResourcePack {
    private final Path basePath;
    private final Gson gson;

    public ResourcePack(Path basePath) {
        this.basePath = basePath;
        this.gson = new GsonBuilder()
                .registerTypeHierarchyAdapter(Key.class, new Key.Serializer())
                .registerTypeHierarchyAdapter(Model.ModelTexture.class, new Model.ModelTexture.Serializer())
                .create();
    }

    public Model getModel(Key key) {
        Path modelPath = basePath.resolve(key.modelPath());
        if (!Files.exists(modelPath)) {
            System.out.println("Model not found: " + modelPath);
        }

        try (InputStream is = Files.newInputStream(modelPath); InputStreamReader reader = new InputStreamReader(is)) {
            return gson.fromJson(reader, Model.class);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean hasTexture(Key key) {
        return basePath.resolve(key.textureMetaPath()).toFile().exists();
    }

    public Texture getTexture(Key key) {
        Path texturePath = basePath.resolve(key.texturePath());
        Path metaPath = basePath.resolve(key.textureMetaPath());

        if (!Files.exists(texturePath)) {
            System.out.println("Texture not found: " + texturePath);
        }

        JsonElement metadata = null;
        if (Files.exists(metaPath)) {
            try (InputStream is = Files.newInputStream(metaPath); InputStreamReader reader = new InputStreamReader(is)) {
                metadata = gson.fromJson(reader, JsonElement.class);
            } catch (Exception e) {
                return null;
            }
        }

        BufferedImage img = null;
        if (Files.exists(texturePath)) {
            try (InputStream is = Files.newInputStream(texturePath); InputStreamReader reader = new InputStreamReader(is)) {
                img = ImageIO.read(is);
            } catch (Exception e) {
                return null;
            }
        }

        return new Texture(texturePath, metadata, img);
    }

    public Map<Key, Model> loadModels(Collection<Key> keys) throws IOException {
        Map<Key, Model> result = new HashMap<>();
        for (Key key : keys) {
            result.put(key, getModel(key));
        }
        return result;
    }

    public Map<Key, Texture> loadTextures(Collection<Key> keys) throws IOException {
        Map<Key, Texture> result = new HashMap<>();
        for (Key key : keys) {
            result.put(key, getTexture(key));
        }
        return result;
    }

    public Set<Key> getTexturesFromModel(Key modelKey) throws IOException {
        Set<Key> textureKeys = new HashSet<>();
        Model model = getModel(modelKey);

        if (model != null) {
            return model.getTextures();
        }

        return textureKeys;
    }

    public List<Key> discoverAllModels() throws IOException {
        return discoverResources(".json", path -> {
            String relativePath = basePath.relativize(path).toString();

            String[] parts = relativePath.split("/");
            if (parts.length >= 4 && "assets".equals(parts[0]) && "models".equals(parts[2])) {
                String namespace = parts[1];
                String modelPath = String.join("/", Arrays.copyOfRange(parts, 3, parts.length)).replace(".json", "");
                return new Key(namespace, modelPath);
            }
            return null;
        });
    }

    public List<Key> discoverAllTextures() throws IOException {
        return discoverResources(".png", path -> {
            String relativePath = basePath.relativize(path).toString();
            String[] parts = relativePath.split("/");
            if (parts.length >= 4 && "assets".equals(parts[0]) && "textures".equals(parts[2])) {
                String namespace = parts[1];
                String texturePath = String.join("/", Arrays.copyOfRange(parts, 3, parts.length)).replace(".png", "");
                return new Key(namespace, texturePath);
            }
            return null;
        });
    }

    private List<Key> discoverResources(String extension, Function<Path, Key> keyMapper) throws IOException {
        List<Key> resources = new ArrayList<>();
        Path typePath = basePath.resolve("assets");

        if (Files.exists(typePath)) {
            try (var s = Files.walk(typePath)) {
                s.filter(path -> path.toString().endsWith(extension)).forEach(path -> {
                    Key key = keyMapper.apply(path);
                    if (key != null) {
                        resources.add(key);
                    }
                });
            }
        }

        return resources;
    }

    public void saveModel(Key key, Model model, Path outputPackPath) throws IOException {
        var path = outputPackPath.resolve(key.modelPath());

        Files.createDirectories(path.getParent());

        String json = gson.toJson(model);
        Files.writeString(path, json);
    }

    public void saveTexture(Key key, BufferedImage bufferedImage, Path outputPackPath) throws IOException {
        var path = outputPackPath.resolve(key.texturePath());

        Files.createDirectories(path.getParent());

        boolean success = ImageIO.write(bufferedImage, "PNG", path.toFile());
        if (!success) {
            throw new IOException("Failed to write PNG image for: " + key);
        }
    }

    public void saveTextureWithMetadata(Key key, BufferedImage bufferedImage, JsonElement metadata, Path outputPackPath) throws IOException {
        saveTexture(key, bufferedImage, outputPackPath);

        if (metadata != null) {
            var metaPath = outputPackPath.resolve(key.textureMetaPath());
            String metaJson = gson.toJson(metadata);
            Files.writeString(metaPath, metaJson);
        }
    }
}