package de.tomalbrc;

import org.jetbrains.annotations.NotNull;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.lang.reflect.Type;

public record Key(String namespace, String path) {
    public static Key of(String combined) {
        if (combined.contains(" ") || !combined.equals(combined.toLowerCase()))
            throw new IllegalArgumentException("Invalid key " + combined);

        if (combined.contains(":")) {
            var split = combined.split(":");
            return new Key(split[0], split[1]);
        }

        return new Key("minecraft", combined);
    }

    @Override
    public @NotNull String toString() {
        return namespace + ":" + path;
    }

    public String modelPath() {
        return "assets/" + namespace + "/models/" + path + ".json";
    }

    public String texturePath() {
        return "assets/" + namespace + "/textures/" + path + ".png";
    }

    public String textureMetaPath() {
        return "assets/" + namespace + "/textures/" + path + ".png.mcmeta";
    }

    public static class Serializer implements JsonSerializer<Key>, JsonDeserializer<Key> {

        @Override
        public JsonElement serialize(Key key, Type typeOfSrc, JsonSerializationContext context) {
            // Write as single string: "namespace:path"
            return new JsonPrimitive(key.namespace() + ":" + key.path());
        }

        @Override
        public Key deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            // Read from single string
            String keyString = json.getAsString();
            return Key.of(keyString);
        }
    }
}
