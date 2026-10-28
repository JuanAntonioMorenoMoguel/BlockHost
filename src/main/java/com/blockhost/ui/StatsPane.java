package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.ProcessStats;
import com.blockhost.core.ProcessStats.Sample;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.ServerProcess;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Pestaña "Rendimiento": CPU y RAM del servidor y del equipo, con gráficas de los últimos minutos.
 * Mide cada pocos segundos en segundo plano, y también cuando la pestaña no se ve (para el resumen de arriba).
 */
final class StatsPane extends VBox {

    private static final int PERIOD_S = 3;
    private static final int POINTS = 100; // 5 minutos

    private final ServerInstance instance;
    private final ServerProcess process;
    private final ProcessStats stats = new ProcessStats();
    private final Consumer<String> summary;

    private final ProgressBar cpuBar = new ProgressBar(0);
    private final Label cpuLabel = new Label();
    private final ProgressBar ramBar = new ProgressBar(0);
    private final Label ramLabel = new Label();
    private final ProgressBar sysCpuBar = new ProgressBar(0);
    private final Label sysCpuLabel = new Label();
    private final ProgressBar sysRamBar = new ProgressBar(0);
    private final Label sysRamLabel = new Label();
    private final Label hint = new Label();
    private final XYChart.Series<Number, Number> cpuSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> ramSeries = new XYChart.Series<>();
    private final NumberAxis cpuX = new NumberAxis();
    private final NumberAxis ramX = new NumberAxis();
    private final ScheduledExecutorService executor;
    private long tick = 0;

    /** @param summary recibe una línea corta ("CPU 12 % · RAM 2,1 / 4 GB") para la cabecera del servidor */
    StatsPane(ServerInstance instance, ServerProcess process, Consumer<String> summary) {
        super(14);
        this.instance = instance;
        this.process = process;
        this.summary = summary;
        setPadding(new Insets(12, 0, 0, 0));

        GridPane bars = new GridPane();
        bars.setHgap(12);
        bars.setVgap(8);
        bars.addRow(0, bold("Servidor"), new Label("CPU"), cpuBar, cpuLabel);
        bars.addRow(1, new Label(""), new Label("RAM"), ramBar, ramLabel);
        bars.addRow(2, bold("Tu PC"), new Label("CPU"), sysCpuBar, sysCpuLabel);
        bars.addRow(3, new Label(""), new Label("RAM"), sysRamBar, sysRamLabel);
        for (ProgressBar b : new ProgressBar[]{cpuBar, ramBar, sysCpuBar, sysRamBar}) {
            b.setPrefWidth(260);
        }
        hint.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        hint.setWrapText(true);

        LineChart<Number, Number> cpuChart = chart("CPU del servidor (%)", cpuX, new NumberAxis(0, 100, 25), cpuSeries);
        NumberAxis ramY = new NumberAxis(0, Math.max(1024, instance.config().ramMb() * 1.25), 1024);
        ramY.setAutoRanging(false);
        LineChart<Number, Number> ramChart = chart("RAM del servidor (MB)", ramX, ramY, ramSeries);
        VBox.setVgrow(cpuChart, Priority.ALWAYS);
        VBox.setVgrow(ramChart, Priority.ALWAYS);

        getChildren().addAll(bars, hint, cpuChart, ramChart);

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stats-" + instance.config().name());
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::sample, 0, PERIOD_S, TimeUnit.SECONDS);
    }

    void dispose() { executor.shutdownNow(); }

    private static Label bold(String s) {
        Label l = new Label(s);
        l.getStyleClass().add(Styles.TEXT_BOLD);
        return l;
    }

    private static LineChart<Number, Number> chart(String title, NumberAxis x, NumberAxis y, XYChart.Series<Number, Number> s) {
        x.setAutoRanging(false);
        x.setTickLabelsVisible(false);
        x.setTickMarkVisible(false);
        x.setMinorTickVisible(false);
        LineChart<Number, Number> c = new LineChart<>(x, y);
        c.setTitle(title);
        c.setLegendVisible(false);
        c.setCreateSymbols(false);
        c.setAnimated(false);
        c.setMinHeight(150);
        c.setPrefHeight(200);
        c.getData().add(s);
        return c;
    }

    private void sample() {
        Sample s = stats.sample(process.handle());
        Platform.runLater(() -> show(s));
    }

    private void show(Sample s) {
        boolean alive = process.isAlive();
        int xmx = instance.config().ramMb();
        if (alive) {
            cpuBar.setProgress(s.processCpu() < 0 ? ProgressBar.INDETERMINATE_PROGRESS : s.processCpu() / 100);
            cpuLabel.setText(s.processCpu() < 0 ? "midiendo..." : String.format("%.0f %%", s.processCpu()));
            ramBar.setProgress(s.processRamMb() < 0 ? 0 : Math.min(1, s.processRamMb() / (double) xmx));
            ramLabel.setText(s.processRamMb() < 0 ? "—"
                    : String.format("%.1f GB de %.1f GB asignados", s.processRamMb() / 1024.0, xmx / 1024.0));
            tick++;
            add(cpuSeries, cpuX, Math.max(0, s.processCpu()));
            if (s.processRamMb() >= 0) add(ramSeries, ramX, s.processRamMb());
            hint.setText(s.processRamMb() > xmx * 1.1
                    ? "Java usa algo más que la RAM asignada (es normal: el mundo cargado vive dentro de esos GB)."
                    : "Java reserva desde el principio la RAM asignada; si la CPU está al 100 % mucho rato, el servidor irá a tirones.");
            summary.accept(String.format("CPU %s · RAM %s", s.processCpu() < 0 ? "—" : String.format("%.0f %%", s.processCpu()),
                    s.processRamMb() < 0 ? "—" : String.format("%.1f / %.1f GB", s.processRamMb() / 1024.0, xmx / 1024.0)));
        } else {
            cpuBar.setProgress(0);
            ramBar.setProgress(0);
            cpuLabel.setText("detenido");
            ramLabel.setText("detenido");
            hint.setText("Inicia el servidor para ver cuánto consume.");
            summary.accept("");
        }
        sysCpuBar.setProgress(s.systemCpu() < 0 ? 0 : s.systemCpu() / 100);
        sysCpuLabel.setText(s.systemCpu() < 0 ? "—" : String.format("%.0f %%", s.systemCpu()));
        sysRamBar.setProgress(s.systemUsedMb() < 0 ? 0 : s.systemUsedMb() / (double) Math.max(1, s.systemTotalMb()));
        sysRamLabel.setText(s.systemUsedMb() < 0 ? "—"
                : String.format("%.1f GB usados de %.1f GB", s.systemUsedMb() / 1024.0, s.systemTotalMb() / 1024.0));
    }

    private void add(XYChart.Series<Number, Number> series, NumberAxis x, double value) {
        series.getData().add(new XYChart.Data<>(tick, value));
        if (series.getData().size() > POINTS) series.getData().remove(0);
        x.setLowerBound(Math.max(0, tick - POINTS));
        x.setUpperBound(Math.max(POINTS, tick));
    }
}
