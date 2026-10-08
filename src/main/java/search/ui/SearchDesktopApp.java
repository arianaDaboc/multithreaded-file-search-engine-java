package search.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import search.SearchConfig;
import search.SearchControl;
import search.SearchEngine;
import search.SearchResult;
import search.db.DatabaseManager;
import search.db.SearchHistoryRepository;
import search.db.SearchRunSummary;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public final class SearchDesktopApp extends Application {
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "file-search-ui-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final TextField directoryField = new TextField(Path.of("src").toAbsolutePath().toString());
    private final TextField queryField = new TextField();
    private final TextField extensionsField = new TextField("java,txt,json,xml,py,cpp");
    private final TextField exclusionsField = new TextField(".git,target,node_modules");
    private final TextField databaseField = new TextField("search-history.db");
    private final CheckBox ignoreCaseBox = new CheckBox("Ignore case");
    private final CheckBox regexBox = new CheckBox("Regular expression");
    private final CheckBox saveHistoryBox = new CheckBox("Save search history");
    private final Spinner<Integer> threadSpinner = new Spinner<>(1, 128, Math.max(1, Runtime.getRuntime().availableProcessors()));
    private final Button searchButton = new Button("Search files");
    private final Label statusLabel = new Label();
    private final Label historyStatusLabel = new Label("History is stored locally in SQLite.");
    private final Label filesValue = new Label("—");
    private final Label matchedValue = new Label("—");
    private final Label occurrencesValue = new Label("—");
    private final Label timeValue = new Label("—");
    private final Label scanDetailsLabel = new Label("Lines, bytes, throughput and file timing will appear after a search.");
    private final TableView<SearchResult> resultsTable = new TableView<>();
    private final ListView<SearchResult.LineMatch> lineList = new ListView<>();
    private final Label resultsPlaceholder = new Label("Search results will appear here.");
    private final TableView<SearchRunSummary> historyTable = new TableView<>();
    private final StackPane pageHost = new StackPane();
    private final Button searchNav = new Button("Search");
    private final Button historyNav = new Button("History");
    private Stage stage;
    private SearchEngine.SearchReport latestReport;
    private SearchConfig latestConfig;
    private SearchControl activeSearchControl;

    @Override public void start(Stage stage) {
        this.stage = stage;
        BorderPane app = new BorderPane();
        app.getStyleClass().add("app-root");
        app.setTop(buildTopBar());
        pageHost.getStyleClass().add("page-host");
        app.setCenter(pageHost);
        showSearchPage();

        Scene scene = new Scene(app, 1240, 820);
        scene.getStylesheets().add(getClass().getResource("/search/ui/search.css").toExternalForm());
        stage.setTitle("File Finder — Local Search");
        stage.setMinWidth(1040);
        stage.setMinHeight(700);
        stage.setScene(scene);
        stage.show();
    }

    private Node buildTopBar() {
        Circle lens = new Circle(10, 10, 6.5);
        lens.getStyleClass().add("brand-lens");
        Line handle = new Line(15, 15, 23, 23);
        handle.getStyleClass().add("brand-handle");
        Pane mark = new Pane(lens, handle);
        mark.setMinSize(30, 30);
        mark.setPrefSize(30, 30);
        mark.setMaxSize(30, 30);
        mark.getStyleClass().add("brand-symbol");
        Label brandName = new Label("needle");
        brandName.getStyleClass().add("brand-name");
        Label brandCaption = new Label("LOCAL FILE SEARCH");
        brandCaption.getStyleClass().add("brand-caption");
        HBox brand = new HBox(10, mark, new VBox(1, brandName, brandCaption));
        brand.getStyleClass().add("brand");
        brand.getChildren().get(1).getStyleClass().add("brand-copy");

        searchNav.getStyleClass().add("nav-button");
        historyNav.getStyleClass().add("nav-button");
        searchNav.setOnAction(event -> showSearchPage());
        historyNav.setOnAction(event -> showHistoryPage());
        HBox navigation = new HBox(5, searchNav, historyNav);
        navigation.getStyleClass().add("top-navigation");

        StackPane top = new StackPane(brand, navigation);
        StackPane.setAlignment(brand, Pos.CENTER);
        StackPane.setAlignment(navigation, Pos.CENTER_RIGHT);
        top.getStyleClass().add("top-bar");
        return top;
    }

    private Node buildSearchPage() {
        VBox page = new VBox(14);
        page.getStyleClass().add("page");
        page.getChildren().addAll(
                pageHeading("Search files", "Find text in a folder and review matching lines."),
                buildSearchCard(),
                buildMetrics(),
                scanDetailsLabel,
                buildResultsArea());
        scanDetailsLabel.getStyleClass().add("scan-details");
        VBox.setVgrow(page.getChildren().get(4), Priority.ALWAYS);
        return page;
    }

    private Node buildSearchCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        HBox titleRow = new HBox(12, sectionHeading("Search", "Set a folder, query, and filters."), statusLabel);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleRow.getChildren().get(0), Priority.ALWAYS);
        statusLabel.getStyleClass().add("status-text");
        statusLabel.setMaxWidth(420);
        card.getChildren().add(titleRow);

        HBox directoryRow = new HBox(10, fieldBlock("DIRECTORY", directoryField), browseButton());
        directoryRow.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setHgrow(directoryRow.getChildren().get(0), Priority.ALWAYS);
        HBox searchActions = new HBox(searchButton);
        searchActions.setAlignment(Pos.BOTTOM_LEFT);
        HBox queryRow = new HBox(10, fieldBlock("QUERY", queryField), searchActions);
        queryRow.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setHgrow(queryRow.getChildren().get(0), Priority.ALWAYS);

        searchButton.getStyleClass().add("primary-button");
        searchButton.setPrefWidth(150);
        searchButton.setOnAction(event -> startSearch());
        queryField.setOnAction(event -> startSearch());

        threadSpinner.setEditable(true);
        threadSpinner.setPrefWidth(92);
        threadSpinner.setTooltip(new Tooltip("Number of worker threads that can scan files in parallel."));
        HBox options = new HBox(17,
                toggleBlock("MATCHING", ignoreCaseBox, regexBox),
                fieldBlock("EXTENSIONS", extensionsField),
                fieldBlock("EXCLUDE DIRECTORIES", exclusionsField),
                fieldBlock("THREADS", threadSpinner));
        options.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(options.getChildren().get(1), Priority.ALWAYS);
        HBox.setHgrow(options.getChildren().get(2), Priority.ALWAYS);
        card.getChildren().addAll(directoryRow, queryRow, options);

        HBox databaseRow = new HBox(12, saveHistoryBox, fieldBlock("DATABASE FILE", databaseField));
        databaseRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(databaseRow.getChildren().get(1), Priority.ALWAYS);
        saveHistoryBox.setSelected(true);
        card.getChildren().add(databaseRow);
        return card;
    }

    private Node toggleBlock(String title, CheckBox... boxes) {
        VBox block = new VBox(6);
        block.getChildren().add(microLabel(title));
        HBox row = new HBox(14, boxes);
        block.getChildren().add(row);
        return block;
    }

    private Node fieldBlock(String title, Node field) {
        VBox block = new VBox(6, microLabel(title), field);
        block.getStyleClass().add("field-block");
        VBox.setVgrow(field, Priority.NEVER);
        return block;
    }

    private Button browseButton() {
        Button browse = new Button("Browse…");
        browse.getStyleClass().add("secondary-button");
        browse.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose a folder to search");
            File current = new File(directoryField.getText());
            if (current.isDirectory()) chooser.setInitialDirectory(current);
            File selected = chooser.showDialog(stage);
            if (selected != null) directoryField.setText(selected.getAbsolutePath());
        });
        return browse;
    }

    private Node buildMetrics() {
        HBox metrics = new HBox(12,
                metricCard("FILES SCANNED", filesValue), metricCard("FILES MATCHED", matchedValue),
                metricCard("OCCURRENCES", occurrencesValue), metricCard("ELAPSED TIME", timeValue));
        for (Node node : metrics.getChildren()) HBox.setHgrow(node, Priority.ALWAYS);
        return metrics;
    }

    private Node metricCard(String title, Label value) {
        VBox card = new VBox(8, microLabel(title), value);
        card.getStyleClass().add("metric-card");
        value.getStyleClass().add("metric-value");
        return card;
    }

    private Node buildResultsArea() {
        HBox area = new HBox(16);
        VBox fileCard = new VBox(12, sectionHeading("Files", "Select a file to inspect its matches."), buildResultsTable());
        VBox lineCard = new VBox(12, sectionHeading("Matching lines", "Line number and surrounding text."), buildLineList());
        fileCard.getStyleClass().add("card");
        lineCard.getStyleClass().add("card");
        fileCard.setMinWidth(0);
        lineCard.setMinWidth(0);
        fileCard.setPrefWidth(620);
        HBox.setHgrow(fileCard, Priority.ALWAYS);
        HBox.setHgrow(lineCard, Priority.ALWAYS);
        HBox.setHgrow(resultsTable, Priority.ALWAYS);
        HBox.setHgrow(lineList, Priority.ALWAYS);
        VBox.setVgrow(resultsTable, Priority.ALWAYS);
        VBox.setVgrow(lineList, Priority.ALWAYS);
        area.getChildren().addAll(fileCard, lineCard);
        // Keep both result panes visible at typical laptop window heights.
        // Their tables/lists provide scrolling when the result set is longer.
        area.setMinHeight(125);
        area.setPrefHeight(185);
        return area;
    }

    private TableView<SearchResult> buildResultsTable() {
        TableColumn<SearchResult, String> path = new TableColumn<>("FILE");
        path.setCellValueFactory(cell -> new ReadOnlyStringWrapper(relativePath(cell.getValue())));
        path.setPrefWidth(380);
        TableColumn<SearchResult, Number> matches = new TableColumn<>("MATCHES");
        matches.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyLongWrapper(cell.getValue().occurrences()));
        matches.setPrefWidth(100);
        resultsTable.getColumns().setAll(java.util.List.of(path, matches));
        resultsTable.setPlaceholder(resultsPlaceholder);
        resultsTable.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> showLines(selected));
        return resultsTable;
    }

    private ListView<SearchResult.LineMatch> buildLineList() {
        lineList.setPlaceholder(new Label("Run a search to see matching lines."));
        lineList.setFixedCellSize(-1);
        lineList.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(SearchResult.LineMatch item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label location = new Label("LINE " + item.lineNumber() + "  ·  " + item.occurrences()
                            + (item.occurrences() == 1 ? " match" : " matches"));
                    location.getStyleClass().add("match-location");
                    Label sourceLine = new Label(item.text());
                    sourceLine.getStyleClass().add("match-source-line");
                    sourceLine.setWrapText(true);
                    sourceLine.maxWidthProperty().bind(lineList.widthProperty().subtract(48));
                    VBox content = new VBox(5, location, sourceLine);
                    content.setFillWidth(true);
                    setText(null);
                    setGraphic(content);
                }
            }
        });
        return lineList;
    }

    private Node buildHistoryPage() {
        VBox page = new VBox(22);
        page.getStyleClass().add("page");
        HBox heading = new HBox(pageHeading("Search history", "Your saved searches and benchmark runs, stored locally."), refreshHistoryButton());
        heading.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(heading.getChildren().get(0), Priority.ALWAYS);
        VBox tableCard = new VBox(14, sectionHeading("Recent runs", "The newest runs appear first."), buildHistoryTable());
        tableCard.getStyleClass().add("card");
        VBox.setVgrow(tableCard, Priority.ALWAYS);
        page.getChildren().addAll(heading, tableCard, historyStatusLabel);
        return page;
    }

    private Button refreshHistoryButton() {
        Button refresh = new Button("Refresh history");
        refresh.getStyleClass().add("secondary-button");
        refresh.setOnAction(event -> loadHistory());
        return refresh;
    }

    private TableView<SearchRunSummary> buildHistoryTable() {
        addHistoryColumn("ID", 55, run -> Long.toString(run.id()));
        addHistoryColumn("CREATED", 160, SearchRunSummary::createdAt);
        addHistoryColumn("QUERY", 170, SearchRunSummary::query);
        addHistoryNumberColumn("THREADS", 70, run -> run.threads());
        addHistoryNumberColumn("SCANNED", 70, run -> run.filesScanned());
        addHistoryNumberColumn("MATCHED", 70, run -> run.filesMatched());
        addHistoryNumberColumn("OCCURRENCES", 100, run -> run.occurrences());
        addHistoryNumberColumn("LINES", 70, run -> run.linesScanned());
        addHistoryColumn("BYTES", 80, run -> formatBytes(run.bytesScanned()));
        addHistoryColumn("FILES/S", 80, run -> String.format(java.util.Locale.ROOT, "%.1f", run.filesPerSecond()));
        addHistoryColumn("AVG/FILE", 90, run -> String.format(java.util.Locale.ROOT, "%.3f ms", run.averageFileSeconds() * 1_000));
        addHistoryNumberColumn("ERRORS", 65, run -> run.errorCount());
        addHistoryColumn("TIME", 80, run -> String.format(java.util.Locale.ROOT, "%.3f s", run.elapsedSeconds()));
        historyTable.setPlaceholder(new Label("No saved searches yet. Run a search with history enabled."));
        return historyTable;
    }

    private void addHistoryColumn(String title, double width, java.util.function.Function<SearchRunSummary, String> value) {
        TableColumn<SearchRunSummary, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        historyTable.getColumns().add(column);
    }

    private void addHistoryNumberColumn(String title, double width, java.util.function.ToLongFunction<SearchRunSummary> value) {
        TableColumn<SearchRunSummary, Number> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyLongWrapper(value.applyAsLong(cell.getValue())));
        historyTable.getColumns().add(column);
    }

    private Node pageHeading(String title, String subtitle) {
        VBox heading = new VBox(6, new Label(title), new Label(subtitle));
        heading.getStyleClass().add("page-heading");
        heading.getChildren().get(0).getStyleClass().add("page-title");
        heading.getChildren().get(1).getStyleClass().add("page-subtitle");
        return heading;
    }

    private Node sectionHeading(String title, String subtitle) {
        VBox heading = new VBox(4, new Label(title), new Label(subtitle));
        heading.getStyleClass().add("section-heading");
        heading.getChildren().get(0).getStyleClass().add("section-title");
        heading.getChildren().get(1).getStyleClass().add("muted-text");
        return heading;
    }

    private Label microLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("micro-label");
        return label;
    }

    private void showSearchPage() {
        pageHost.getChildren().setAll(buildSearchPage());
        setActiveNavigation(searchNav);
    }

    private void showHistoryPage() {
        pageHost.getChildren().setAll(buildHistoryPage());
        setActiveNavigation(historyNav);
        loadHistory();
    }

    private void setActiveNavigation(Button active) {
        searchNav.getStyleClass().remove("active");
        historyNav.getStyleClass().remove("active");
        if (!active.getStyleClass().contains("active")) active.getStyleClass().add("active");
    }

    private void startSearch() {
        if (searchButton.isDisabled()) return;
        String query = queryField.getText().trim();
        String directory = directoryField.getText().trim();
        if (query.isEmpty()) { statusLabel.setText("Enter a query to start searching."); queryField.requestFocus(); return; }
        Path root;
        try { root = Path.of(directory); }
        catch (RuntimeException error) { statusLabel.setText("Enter a valid directory path."); return; }
        if (directory.isEmpty() || !Files.isDirectory(root)) { statusLabel.setText("Choose an existing directory first."); return; }
        SearchConfig config;
        try {
            config = new SearchConfig(root, query, !ignoreCaseBox.isSelected(), parseCsv(extensionsField.getText()), parseCsv(exclusionsField.getText()), regexBox.isSelected());
            if (saveHistoryBox.isSelected() && databaseField.getText().isBlank()) throw new IllegalArgumentException("Choose a database file or disable search history.");
        } catch (RuntimeException error) { statusLabel.setText(error.getMessage()); return; }
        latestConfig = config;

        int workers = threadSpinner.getValue();
        boolean persist = saveHistoryBox.isSelected();
        Path databasePath = Path.of(databaseField.getText().isBlank() ? "search-history.db" : databaseField.getText().trim());
        AtomicReference<SearchControl> controlRef = new AtomicReference<>();
        SearchControl control = new SearchControl(progress -> Platform.runLater(() -> {
            if (activeSearchControl == controlRef.get()) {
                statusLabel.setText("Scanning files… " + progress.filesScanned() + " / " + progress.filesDiscovered());
            }
        }));
        controlRef.set(control);
        activeSearchControl = control;
        searchButton.setDisable(true);
        searchButton.setText("Searching…");
        statusLabel.setText("Scanning files with " + workers + " worker thread(s)…");
        Task<SearchOutcome> task = new Task<>() {
            @Override protected SearchOutcome call() throws Exception {
                SearchEngine.SearchReport report = new SearchEngine(config).search(workers, control);
                long runId = -1;
                if (persist && !report.cancelled()) runId = new SearchHistoryRepository(new DatabaseManager(databasePath)).save(config, report, workers);
                return new SearchOutcome(report, runId);
            }
        };
        task.setOnSucceeded(event -> {
            SearchOutcome outcome = task.getValue();
            latestReport = outcome.report();
            updateResults(config, latestReport);
            statusLabel.setText(latestReport.cancelled() ? "Search cancelled · showing completed results" : "");
            if (!latestReport.errors().isEmpty()) statusLabel.setText("Search finished with "
                    + latestReport.errors().size() + " unreadable file(s)" + errorSummary(config, latestReport));
            finishSearchControls();
        });
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            statusLabel.setText(error == null ? "Search failed." : "Search failed: " + error.getMessage());
            finishSearchControls();
        });
        searchExecutor.submit(task);
    }

    private void finishSearchControls() {
        activeSearchControl = null;
        searchButton.setDisable(false);
        searchButton.setText("Search files");
    }

    private String errorSummary(SearchConfig config, SearchEngine.SearchReport report) {
        if (report.errors().isEmpty()) return "";
        var first = report.errors().get(0);
        Path displayPath = first.file().startsWith(config.root()) ? config.root().relativize(first.file()) : first.file();
        return " · " + report.errors().size() + " warning(s); " + displayPath + ": " + first.reason();
    }

    private Set<String> parseCsv(String text) {
        return Arrays.stream(text.split(",")).map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    private void updateResults(SearchConfig config, SearchEngine.SearchReport report) {
        filesValue.setText(Integer.toString(report.filesScanned()));
        matchedValue.setText(Integer.toString(report.results().size()));
        occurrencesValue.setText(Long.toString(report.occurrences()));
        timeValue.setText(String.format(java.util.Locale.ROOT, "%.3f s", report.elapsedSeconds()));
        scanDetailsLabel.setText(String.format(java.util.Locale.ROOT,
                "SCAN DETAILS   ·   %,d lines   ·   %s read   ·   %,.1f files/s   ·   %.3f ms/file   ·   %d errors",
                report.linesScanned(), formatBytes(report.bytesScanned()), report.filesPerSecond(),
                report.averageFileSeconds() * 1_000, report.errorCount()));
        resultsTable.getItems().setAll(report.results());
        if (!report.results().isEmpty()) {
            resultsPlaceholder.setText("Search results will appear here.");
            resultsTable.getSelectionModel().selectFirst();
        } else {
            resultsPlaceholder.setText("No files matched. Try a different spelling or enable Ignore case.");
            lineList.getItems().clear();
            lineList.setPlaceholder(new Label("No matching lines to display."));
        }
        if (!report.errors().isEmpty()) statusLabel.setText("Search finished with " + report.errors().size() + " unreadable file(s).");
    }

    private void showLines(SearchResult result) {
        lineList.getItems().clear();
        if (result != null) {
            lineList.setPlaceholder(new Label("No matching lines to display."));
            lineList.getItems().setAll(result.matches());
        } else {
            lineList.setPlaceholder(new Label("Select a matching file to view its lines."));
        }
    }

    private String relativePath(SearchResult result) {
        return latestConfig == null ? result.file().toString() : latestConfig.root().relativize(result.file()).toString();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1_000) return bytes + " B";
        if (bytes < 1_000_000) return String.format(java.util.Locale.ROOT, "%.2f kB", bytes / 1_000.0);
        if (bytes < 1_000_000_000) return String.format(java.util.Locale.ROOT, "%.2f MB", bytes / 1_000_000.0);
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0);
    }

    private void loadHistory() {
        try {
            Path database = Path.of(databaseField.getText().isBlank() ? "search-history.db" : databaseField.getText().trim());
            var repository = new SearchHistoryRepository(new DatabaseManager(database));
            historyTable.getItems().setAll(repository.findLatest(100));
            historyStatusLabel.setText(historyTable.getItems().isEmpty() ? "No saved searches yet." : "Matching files and line text are stored with each run.");
        } catch (Exception error) {
            historyStatusLabel.setText("Could not load search history: " + error.getMessage());
        }
    }

    private record SearchOutcome(SearchEngine.SearchReport report, long runId) { }

    @Override public void stop() { searchExecutor.shutdownNow(); }

    public static void main(String[] args) { launch(args); }
}
