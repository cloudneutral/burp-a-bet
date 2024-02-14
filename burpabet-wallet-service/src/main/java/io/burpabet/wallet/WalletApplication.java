package io.burpabet.wallet;

import java.util.Arrays;
import java.util.LinkedList;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.annotation.Order;
import org.springframework.shell.jline.InteractiveShellRunner;
import org.springframework.shell.jline.PromptProvider;

import ch.qos.logback.classic.Level;

@Configuration
@ConfigurationPropertiesScan(basePackageClasses = WalletApplication.class)
@SpringBootApplication(exclude = {
        JdbcRepositoriesAutoConfiguration.class,
        DataSourceAutoConfiguration.class,
})
@EnableAspectJAutoProxy(proxyTargetClass = true)
@Order(InteractiveShellRunner.PRECEDENCE - 100)
public class WalletApplication implements PromptProvider {
    public static void main(String[] args) {
        LinkedList<String> argsList = new LinkedList<>(Arrays.asList(args));
        LinkedList<String> passThroughArgs = new LinkedList<>();

        while (!argsList.isEmpty()) {
            String arg = argsList.pop();
            if (arg.startsWith("--")) {
                if (arg.equals("--noshell")) {
                    System.setProperty("spring.shell.interactive.enabled", "false");
                } else {
                    passThroughArgs.add(arg);
                }
            } else {
                passThroughArgs.add(arg);
            }
        }

        new SpringApplicationBuilder(WalletApplication.class)
                .logStartupInfo(true)
                .web(WebApplicationType.SERVLET)
                .run(passThroughArgs.toArray(new String[] {}));
    }

    @Override
    public AttributedString getPrompt() {
        ch.qos.logback.classic.LoggerContext loggerContext = (ch.qos.logback.classic.LoggerContext) LoggerFactory
                .getILoggerFactory();
        ch.qos.logback.classic.Logger logger = loggerContext.getLogger("io.burpabet");
        int fg =
                switch (logger.getLevel().toInt()) {
                    case Level.TRACE_INT -> AttributedStyle.MAGENTA;
                    case Level.DEBUG_INT -> AttributedStyle.CYAN;
                    case Level.INFO_INT -> AttributedStyle.GREEN;
                    case Level.WARN_INT -> AttributedStyle.YELLOW;
                    case Level.ERROR_INT -> AttributedStyle.RED;
                    default -> AttributedStyle.GREEN;
                };
        return new AttributedString("wallet:$ ",
                AttributedStyle.DEFAULT.foreground(fg));
    }

}
