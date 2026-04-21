package com.timetable.operator.schedule.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timetable.operator.common.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LocalGemmaScheduleClient {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public List<ImportedScheduleBlock> normalize(String rawText) {
        if (!appProperties.ai().enabled()) {
            throw new IllegalStateException("Local Gemma integration is disabled.");
        }

        Path scriptPath = Path.of(appProperties.ai().gemmaScriptPath()).toAbsolutePath().normalize();
        if (!Files.exists(scriptPath)) {
            throw new IllegalStateException("Gemma schedule normalizer not found: " + scriptPath);
        }
        String pythonExecutable = resolvePythonExecutable(scriptPath);

        Path inputFile = null;
        try {
            inputFile = Files.createTempFile("gemma-schedule-", ".txt");
            Files.writeString(inputFile, rawText, StandardCharsets.UTF_8);

            ProcessBuilder processBuilder = new ProcessBuilder(
                    pythonExecutable,
                    scriptPath.toString(),
                    "--input-file",
                    inputFile.toString()
            );
            processBuilder.directory(scriptPath.getParent().toFile());
            processBuilder.environment().put("PYTHONIOENCODING", "utf-8");

            Process process = processBuilder.start();
            CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream());
            CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream());
            boolean finished = process.waitFor(appProperties.ai().timeoutSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IllegalStateException("Gemma schedule normalization timed out.");
            }

            String stdout = stdoutFuture.get();
            String stderr = stderrFuture.get();

            if (process.exitValue() != 0) {
                String details = stderr.isBlank() ? stdout : stderr;
                throw new IllegalStateException("Gemma schedule normalization failed: " + details);
            }

            ScheduleImportPayload payload = objectMapper.readValue(stdout, ScheduleImportPayload.class);
            return payload.blocks();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to execute local Gemma schedule importer.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gemma schedule normalization was interrupted.", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Failed to read Gemma schedule normalization result.", exception);
        } finally {
            if (inputFile != null) {
                try {
                    Files.deleteIfExists(inputFile);
                } catch (IOException ignored) {
                    // Temporary input cleanup failure should not mask the original import result.
                }
            }
        }
    }

    private CompletableFuture<String> readStreamAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read Gemma process output.", exception);
            }
        });
    }

    private String resolvePythonExecutable(Path scriptPath) {
        String configuredExecutable = appProperties.ai().pythonExecutable();
        Path configuredPath = Path.of(configuredExecutable).toAbsolutePath().normalize();
        if (Files.exists(configuredPath)) {
            return configuredPath.toString();
        }

        Path localVenvPython = scriptPath.getParent()
                .resolve(".venv")
                .resolve("Scripts")
                .resolve("python.exe")
                .toAbsolutePath()
                .normalize();
        if (Files.exists(localVenvPython)) {
            return localVenvPython.toString();
        }

        return "py";
    }

    public record ScheduleImportPayload(
            List<ImportedScheduleBlock> blocks,
            List<String> warnings
    ) {
    }

    public record ImportedScheduleBlock(
            DayOfWeek dayOfWeek,
            LocalTime startTime,
            LocalTime endTime,
            String activity,
            String category,
            String note
    ) {
    }
}
