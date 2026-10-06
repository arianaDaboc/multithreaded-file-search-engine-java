package search.ui;

import javafx.application.Application;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import search.SearchConfig;
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
    private final Label statusLabel = new Label("Ready when you are.");
    private final Label historyStatusLabel = new Label("History is stored locally in SQLite.");
    private final Label filesValue = new Label("—");
    private final Label matchedValue = new Label("—");
    private final Label occurrencesValue = new Label("—");
    private final Label timeValue = new Label("—");
    private final TableView<SearchResult> resultsTable = new TableView<>();
    private final ListView<SearchResult.LineMatch> lineList = new ListView<>();
    private final TableView<SearchRunSummary> historyTable = new TableView<>();
    private final StackPane pageHost = new StackPane();
    private final Button searchNav = new Button("Search");
    private final Button historyNav = new Button("History");
    private Stage stage;
    private SearchEngine.SearchReport latestReport;
    private SearchConfig latestConfig;

    @Override public void start(Stage stage) {
        this.stage = stage;
        BorderPane app = new BorderPane();
        app.getStyleClass().add("app-root");
        app.setLeft(buildSidebar());
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

    private Node buildSidebar() {
        VBox sidebar = new VBox(22);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(226);

        HBox brand = new HBox(10, new Label("N"), new VBox(2, new Label("NEEDLE"), mutedLabel("LOCAL FILE SEARCH")));
        brand.getStyleClass().add("brand");
        brand.getChildren().get(0).getStyleClass().add("brand-mark");
        sidebar.getChildren().addAll(brand, mutedLabel("TOOLS"));

        searchNav.getStyleClass().add("nav-button");
        historyNav.getStyleClass().add("nav-button");
        searchNav.setMaxWidth(Double.MAX_VALUE);
        historyNav.setMaxWidth(Double.MAX_VALUE);
        searchNav.setOnAction(event -> showSearchPage());
        historyNav.setOnAction(event -> showHistoryPage());
        sidebar.getChildren().addAll(searchNav, historyNav);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        VBox localNote = new VBox(7, new Label("LOCAL DATA"), mutedLabel("Search runs on this machine."), mutedLabel("History: SQLite"));
        localNote.getStyleClass().add("sidebar-note");
        sidebar.getChildren().addAll(spacer, localNote);
        return sidebar;
    }

    private Node buildTopBar() {
        HBox top = new HBox(new Label("LOCAL WORKSPACE"));
        top.setAlignment(Pos.CENTER_RIGHT);
        top.getStyleClass().add("top-bar");
        return top;
    }

    private Node buildSearchPage() {
        VBox page = new VBox(22);
        page.getStyleClass().add("page");
        page.getChildren().addAll(
                pageHeading("Search files", "Find text in a folder and review matching lines."),
                buildSearchCard(),
                buildMetrics(),
                buildResultsArea(),
                statusLabel);
        VBox.setVgrow(page.getChildren().get(3), Priority.ALWAYS);
        return page;
    }

    private Node buildSearchCard() {
        VBox card = new VBox(16);
        card.getStyleClass().add("card");
        card.getChildren().addAll(sectionHeading("Search", "Set a folder, query, and filters."));

        HBox directoryRow = new HBox(10, fieldBlock("DIRECTORY", directoryField), browseButton());
        directoryRow.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setHgrow(directoryRow.getChildren().get(0), Priority.ALWAYS);
        HBox queryRow = new HBox(10, fieldBlock("QUERY", queryField), searchButton);
        queryRow.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setHgrow(queryRow.getChildren().get(0), Priority.ALWAYS);

        searchButton.getStyleClass().add("primary-button");
        searchButton.setPrefWidth(150);
        searchButton.setOnAction(event -> startSearch());
        queryField.setOnAction(event -> startSearch());

        threadSpinner.setEditable(true);
        threadSpinner.setPrefWidth(92);
        HBox options = new HBox(17,
                toggleBlock("MATCHING", ignoreCaseBox, regexBox),
                fieldBlock("EXTENSIONS", extensionsField),
                fieldBlock("EXCLUDE DIRECTORIES", exclusionsField),
                fieldBlock("WORKERS", threadSpinner));
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
        area.setMinHeight(300);
        area.setPrefHeight(360);
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
        resultsTable.setPlaceholder(new Label("Search results will appear here."));
        resultsTable.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> showLines(selected));
        return resultsTable;
    }

    private ListView<SearchResult.LineMatch> buildLineList() {
        lineList.setPlaceholder(new Label("Choose a matching file."));
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
        addHistoryColumn("QUERY", 200, SearchRunSummary::query);
        addHistoryNumberColumn("THREADS", 80, run -> run.threads());
        addHistoryNumberColumn("SCANNED", 85, run -> run.filesScanned());
        addHistoryNumberColumn("MATCHED", 85, run -> run.filesMatched());
        addHistoryNumberColumn("OCCURRENCES", 110, run -> run.occurrences());
        addHistoryColumn("TIME", 85, run -> String.format(java.util.Locale.ROOT, "%.3f s", run.elapsedSeconds()));
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

    private Label mutedLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted-text");
        return label;
    }

    private void showSearchPage() {
        pageHost.getChildren().setAll(buildSearchPage());
        searchNav.getStyleClass().add("active");
        historyNav.getStyleClass().remove("active");
    }

    private void showHistoryPage() {
        pageHost.getChildren().setAll(buildHistoryPage());
        historyNav.getStyleClass().add("active");
        searchNav.getStyleClass().remove("active");
        loadHistory();
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
        searchButton.setDisable(true);
        searchButton.setText("Searching…");
        statusLabel.setText("Scanning files with " + workers + " worker thread(s)…");
        Task<SearchOutcome> task = new Task<>() {
            @Override protected SearchOutcome call() throws Exception {
                SearchEngine.SearchReport report = new SearchEngine(config).search(workers);
                long runId = -1;
                if (persist) runId = new SearchHistoryRepository(new DatabaseManager(databasePath)).save(config, report, workers);
                return new SearchOutcome(report, runId);
            }
        };
        task.setOnSucceeded(event -> {
            SearchOutcome outcome = task.getValue();
            latestReport = outcome.report();
            updateResults(config, latestReport);
            statusLabel.setText("Search complete" + (outcome.runId() > 0 ? " · saved to local history as run #" + outcome.runId() : "")
                    + (latestReport.errors().isEmpty() ? "" : " · " + latestReport.errors().size() + " unreadable file(s)"));
            searchButton.setDisable(false);
            searchButton.setText("Search files");
        });
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            statusLabel.setText(error == null ? "Search failed." : "Search failed: " + error.getMessage());
            searchButton.setDisable(false);
            searchButton.setText("Search files");
        });
        searchExecutor.submit(task);
    }

    private Set<String> parseCsv(String text) {
        return Arrays.stream(text.split(",")).map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    private void updateResults(SearchConfig config, SearchEngine.SearchReport report) {
        filesValue.setText(Integer.toString(report.filesScanned()));
        matchedValue.setText(Integer.toString(report.results().size()));
        occurrencesValue.setText(Long.toString(report.occurrences()));
        timeValue.setText(String.format(java.util.Locale.ROOT, "%.3f s", report.elapsedSeconds()));
        resultsTable.getItems().setAll(report.results());
        if (!report.results().isEmpty()) resultsTable.getSelectionModel().selectFirst();
        else lineList.getItems().clear();
        if (!report.errors().isEmpty()) statusLabel.setText("Search finished with " + report.errors().size() + " unreadable file(s).");
    }

    private void showLines(SearchResult result) {
        lineList.getItems().clear();
        if (result != null) lineList.getItems().setAll(result.matches());
    }

    private String relativePath(SearchResult result) {
        return latestConfig == null ? result.file().toString() : latestConfig.root().relativize(result.file()).toString();
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
