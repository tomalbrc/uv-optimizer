package org.example;

import com.google.gson.*;

import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Model {
    public Key parent;
    public Map<String, ModelTexture> textures;
    public List<Element> elements;
    public JsonElement display;

    public Set<Key> getTextures() {
        Set<Key> textureKeys = new HashSet<>();
        if (textures != null) {
            for (Map.Entry<String, ModelTexture> entry : textures.entrySet()) {
                Model.ModelTexture modelTexture = entry.getValue();
                if (modelTexture.isKey() && !entry.getKey().equals("particle")) {
                    textureKeys.add(modelTexture.key());
                }
            }
        }

        return textureKeys;
    }

    public record ModelTexture(Key key, String reference) {
        public boolean isKey() { return key != null; }

        public static class Serializer implements JsonSerializer<Model.ModelTexture>, JsonDeserializer<ModelTexture> {
            @Override
            public JsonElement serialize(Model.ModelTexture texture, Type typeOfSrc, JsonSerializationContext context) {
                if (texture.isKey()) {
                    return new JsonPrimitive(texture.key().toString());
                } else {
                    return new JsonPrimitive(texture.reference());
                }
            }

            @Override
            public Model.ModelTexture deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                    throws JsonParseException {
                String textureString = json.getAsString();

                if (textureString.startsWith("#")) {
                    return new Model.ModelTexture(null, textureString);
                } else {
                    try {
                        Key key = Key.of(textureString);
                        return new Model.ModelTexture(key, textureString);
                    } catch (Exception e) {
                        return new Model.ModelTexture(null, textureString);
                    }
                }
            }
        }
    }

    public static class Element {
        public float[] from; // [x1, y1, z1]
        public float[] to;   // [x2, y2, z2]

        public Boolean shade;
        public Element.Rotation rotation;
        public Map<String, Element.Face> faces;

        public static class Face {
            public float[] uv;
            public String texture;
            public String cullface;
            public Integer rotation;
            public Integer tintindex;
        }

        public static class Rotation {
            public float[] origin;
            public String axis;
            public Float angle;
            public Boolean rescale;
        }
    }
}
