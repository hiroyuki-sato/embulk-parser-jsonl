package org.embulk.parser.jsonl;

import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigException;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskSource;
import org.embulk.spi.Column;
import org.embulk.spi.ColumnConfig;
import org.embulk.spi.DataException;
import org.embulk.spi.Exec;
import org.embulk.spi.FileInput;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.PageOutput;
import org.embulk.spi.ParserPlugin;
import org.embulk.spi.Schema;
import org.embulk.spi.SchemaConfig;
import org.embulk.spi.json.JsonParseException;
import org.embulk.spi.json.JsonParser;
import org.embulk.spi.time.TimestampParser;
import org.embulk.spi.type.Type;
import org.embulk.spi.util.LineDecoder;
import org.embulk.spi.util.Timestamps;
import org.msgpack.value.Value;
import org.slf4j.Logger;

import java.util.Map;

import static org.msgpack.value.ValueFactory.newString;

public class JsonlParserPlugin
        implements ParserPlugin
{
    @Deprecated
    public interface JsonlColumnOption
            extends Task
    {
        @Config("type")
        @ConfigDefault("null")
        Optional<Type> getType();
    }

    public interface TypecastColumnOption
            extends Task
    {
        @Config("typecast")
        @ConfigDefault("null")
        public Optional<Boolean> getTypecast();
    }

    public interface PluginTask
            extends Task, LineDecoder.DecoderTask, TimestampParser.Task
    {
        @Config("columns")
        @ConfigDefault("null")
        Optional<SchemaConfig> getSchemaConfig();

        @Config("schema")
        @ConfigDefault("null")
        @Deprecated
        Optional<SchemaConfig> getOldSchemaConfig();

        @Config("stop_on_invalid_record")
        @ConfigDefault("false")
        boolean getStopOnInvalidRecord();

        @Config("default_typecast")
        @ConfigDefault("true")
        Boolean getDefaultTypecast();

        @Config("column_options")
        @ConfigDefault("{}")
        @Deprecated
        Map<String, JsonlColumnOption> getColumnOptions();

        @Config("null_string")
        @ConfigDefault("null")
        Optional<String> getNullString();
    }

    private final Logger log;

    private String line = null;
    private long lineNumber = 0;
    private Map<String, Value> columnNameValues;

    public JsonlParserPlugin()
    {
        this.log = Exec.getLogger(JsonlParserPlugin.class);
    }

    @Override
    public void transaction(ConfigSource configSource, Control control)
    {
        PluginTask task = configSource.loadConfig(PluginTask.class);

        if (! task.getColumnOptions().isEmpty()) {
            log.warn("embulk-parser-jsonl: \"column_options\" option is deprecated, specify type directly to \"columns\" option with typecast: true (default: true).");
        }

        SchemaConfig schemaConfig = getSchemaConfig(task);
        ImmutableList.Builder<Column> columns = ImmutableList.builder();
        for (int i = 0; i < schemaConfig.getColumnCount(); i++) {
            ColumnConfig columnConfig = schemaConfig.getColumn(i);
            Type type = getType(task, columnConfig);
            columns.add(new Column(i, columnConfig.getName(), type));
        }
        control.run(task.dump(), new Schema(columns.build()));
    }

    private static Type getType(PluginTask task, ColumnConfig columnConfig)
    {
        JsonlColumnOption columnOption = columnOptionOf(task.getColumnOptions(), columnConfig.getName());
        return columnOption.getType().or(columnConfig.getType());
    }

    // this method is to keep the backward compatibility of 'schema' option.
    private SchemaConfig getSchemaConfig(PluginTask task)
    {
        if (task.getOldSchemaConfig().isPresent()) {
            log.warn("Please use 'columns' option instead of 'schema' because the 'schema' option is deprecated. The next version will stop 'schema' option support.");
        }

        if (task.getSchemaConfig().isPresent()) {
            return task.getSchemaConfig().get();
        }
        else if (task.getOldSchemaConfig().isPresent()) {
            return task.getOldSchemaConfig().get();
        }
        else {
            throw new ConfigException("Attribute 'columns' is required but not set");
        }
    }

    @Override
    public void run(TaskSource taskSource, Schema schema, FileInput input, PageOutput output)
    {
        PluginTask task = taskSource.loadTask(PluginTask.class);

        setColumnNameValues(schema);

        final SchemaConfig schemaConfig = getSchemaConfig(task);
        final TimestampParser[] timestampParsers = Timestamps.newTimestampColumnParsers(task, schemaConfig);
        final LineDecoder decoder = newLineDecoder(input, task);
        final JsonParser jsonParser = newJsonParser();
        final boolean stopOnInvalidRecord = task.getStopOnInvalidRecord();

        try (final PageBuilder pageBuilder = new PageBuilder(Exec.getBufferAllocator(), schema, output)) {
            ColumnVisitorImpl visitor = new ColumnVisitorImpl(task, schema, pageBuilder, timestampParsers);

            while (decoder.nextFile()) { // TODO this implementation should be improved with new JsonParser API on Embulk v0.8.3
                lineNumber = 0;

                while ((line = decoder.poll()) != null) {
                    lineNumber++;

                    try {
                        Value value = jsonParser.parse(line);

                        if (!value.isMapValue()) {
                            throw new JsonRecordValidateException("Json string is not representing map value.");
                        }

                        final Map<Value, Value> record = value.asMapValue().map();
                        for (Column column : schema.getColumns()) {
                            Value v = record.get(getColumnNameValue(column));
                            visitor.setValue(v);
                            column.visit(visitor);
                        }

                        pageBuilder.addRecord();
                    }
                    catch (JsonRecordValidateException | JsonParseException e) {
                        if (stopOnInvalidRecord) {
                            throw new DataException(String.format("Invalid record at line %d: %s", lineNumber, line), e);
                        }
                        log.warn(String.format("Skipped line %d (%s): %s", lineNumber, e.getMessage(), line));
                    }
                }
            }

            pageBuilder.finish();
        }
    }

    private void setColumnNameValues(Schema schema)
    {
        ImmutableMap.Builder<String, Value> builder = ImmutableMap.builder();
        for (Column column : schema.getColumns()) {
            String name = column.getName();
            builder.put(name, newString(name));
        }
        columnNameValues = builder.build();
    }

    private Value getColumnNameValue(Column column)
    {
        return columnNameValues.get(column.getName());
    }

    public LineDecoder newLineDecoder(FileInput input, PluginTask task)
    {
        return new LineDecoder(input, task);
    }

    public JsonParser newJsonParser()
    {
        return new JsonParser();
    }

    private static JsonlColumnOption columnOptionOf(Map<String, JsonlColumnOption> columnOptions, String columnName)
    {
        return Optional.fromNullable(columnOptions.get(columnName)).or(
                // default column option
                new Supplier<JsonlColumnOption>()
                {
                    public JsonlColumnOption get()
                    {
                        return Exec.newConfigSource().loadConfig(JsonlColumnOption.class);
                    }
                });
    }

}
