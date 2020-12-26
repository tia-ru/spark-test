package tia.test.spark.hint;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.dropwizard.DropwizardConfig;
import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;
import io.micrometer.core.instrument.util.HierarchicalNameMapper;

import java.util.concurrent.TimeUnit;

public class Meter {
    private final MeterRegistry registry;
    public final ConsoleReporter consoleReporter;

    public Meter() {
        MetricRegistry dropwizardRegistry = new MetricRegistry();
        registry = createRegistry(dropwizardRegistry);
        consoleReporter = createConsoleReporter(dropwizardRegistry);
    }

    public MeterRegistry getRegistry() {
        return registry;
    }


    private static ConsoleReporter createConsoleReporter(MetricRegistry dropwizardRegistry) {
        ConsoleReporter reporter = ConsoleReporter.forRegistry(dropwizardRegistry)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();
        //reporter.start(10, TimeUnit.SECONDS);
        return reporter;
    }


    private MeterRegistry createRegistry(MetricRegistry dropwizardRegistry) {
        DropwizardConfig consoleConfig = new DropwizardConfig() {

            @Override
            public String prefix() {
                return "console";
            }

            @Override
            public String get(String key) {
                return null;
            }

        };

        return new DropwizardMeterRegistry(consoleConfig, dropwizardRegistry, HierarchicalNameMapper.DEFAULT, Clock.SYSTEM) {
            @Override
            protected Double nullGaugeValue() {
                return null;
            }
        };
    }
    public void reportAndClear(){
        consoleReporter.report();
        registry.clear();
    }

    public void clear(){
        registry.clear();
    }

    public void close(){
        consoleReporter.close();
        registry.close();
    }
}
