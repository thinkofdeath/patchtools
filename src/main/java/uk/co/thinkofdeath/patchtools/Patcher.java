package uk.co.thinkofdeath.patchtools;

import uk.co.thinkofdeath.patchtools.patch.PatchClasses;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class Patcher {

    private final ClassSet classSet;

    public Patcher(ClassSet classSet) {
        this.classSet = classSet;
    }

    public void apply(InputStream inputStream) {
        apply(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    public void apply(Reader reader) {
        apply(new BufferedReader(reader));
    }

    public void apply(BufferedReader reader) {
        try (BufferedReader ignored = reader) {
            new PatchClasses(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ClassSet getClasses() {
        return classSet;
    }
}
