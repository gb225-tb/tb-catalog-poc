package com.tailoredbrand.config;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;

public class YamlConfigLoader {

    public static AppConfig load(String fileName) {
        Yaml yaml = new Yaml();
        InputStream input = YamlConfigLoader.class.getClassLoader().getResourceAsStream(fileName);

        if (input == null) {
            throw new RuntimeException("YAML config file not found: " + fileName);
        }

        return yaml.loadAs(input, AppConfig.class);
    }
}

