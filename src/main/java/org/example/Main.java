package org.example;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) throws IOException {
        ResourcePack resourcePack = new ResourcePack(Path.of(args[0]));
        List<Key> models = resourcePack.discoverAllModels().stream().filter(x -> {
            Model m = resourcePack.getModel(x);
            return m == null || m.parent != null || m.elements == null || m.getTextures().size() == 1;
        }).toList();

        List<Key> textures = resourcePack.discoverAllTextures();

        for (Key x : models) {
            System.out.println("Models to optimize: " + x.toString());
        }

        Optimizer optimizer = new Optimizer(resourcePack, Path.of(args[1]));
        optimizer.optimize();
    }
}
