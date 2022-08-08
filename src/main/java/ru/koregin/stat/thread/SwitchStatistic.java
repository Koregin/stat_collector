package ru.koregin.stat.thread;

import net.sf.expectit.Expect;
import net.sf.expectit.ExpectBuilder;
import org.apache.commons.net.telnet.TelnetClient;
import ru.koregin.stat.SqlStore;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static net.sf.expectit.matcher.Matchers.contains;

/**
 * Программа подключается к удаленным коммутаторам и получает статистику по гигабитным интерфейсам 0/1 и 0/2
 * Полученный вывод парсится и сохраняется в html страницу для дальнейшего просмотра.
 * Данные о коммутаторах берутcя из БД MySQL
 * Данный вариант поддерживает многопоточность
 *
 * @author Evgeny Koregin
 * @version 1.0
 */

public class SwitchStatistic {

    private final static List<String> GOODCOUNTERS = List.of("Bytes", "Frames", "Multicast frames", "Broadcast frames", "Tagged frames");
    private final String htmlPathFile;
    private final String switchPassword;
    private final static String LS = System.lineSeparator();

    public SwitchStatistic() {
        try (InputStream in = SwitchStatistic.class.getClassLoader()
                .getResourceAsStream("app.properties")) {
            Properties config = new Properties();
            config.load(in);
            this.htmlPathFile = config.getProperty("html-path-file");
            this.switchPassword = config.getProperty("switch-password");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String getHtmlPathFile() {
        return htmlPathFile;
    }

    public String getSwitchPassword() {
        return switchPassword;
    }

    /**
     * Для многопоточности используется .parallelStream
     * @param args
     */
    public static void main(String[] args) {

        long timeStart = System.currentTimeMillis();

        SwitchStatistic switchStatistic = new SwitchStatistic();

        Map<String, String> switches = new HashMap<>();

        try (SqlStore store = new SqlStore()) {
            store.init();
            switches = store.findAllSwitches();
        } catch (Exception e) {
            e.printStackTrace();
        }

        Map<String, List<List<Map<String, Long>>>> result = new ConcurrentHashMap<>();
        AtomicInteger count = new AtomicInteger(1);

        /* This variant for small cpu mashines */
        final ExecutorService executor = Executors.newFixedThreadPool(20);
        for (String key : switches.keySet()) {
            String swIp = switches.get(key);
            executor.execute(() -> {
                System.out.println(count.getAndIncrement() + " Коммутатор: " + key);
                try {
                    result.putIfAbsent(key, List.of(outParser(getSwitchInfo(swIp, 1, switchStatistic.switchPassword)),
                            outParser(getSwitchInfo(swIp, 2, switchStatistic.getSwitchPassword()))));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }
        executor.shutdown();
        while (!executor.isTerminated()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        /* This variant use parallelStream() method for collections
        switches.entrySet()
                .parallelStream()
                .forEach(entry -> {
                    String swIp = entry.getValue();
                    System.out.println(count.getAndIncrement() + " Коммутатор: " + entry.getKey());
                    try {
                        result.putIfAbsent(entry.getKey(), List.of(outParser(getSwitchInfo(swIp, 1, switchStatistic.switchPassword)),
                                outParser(getSwitchInfo(swIp, 2, switchStatistic.getSwitchPassword()))));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
         */
        createHtml(result, switchStatistic.getHtmlPathFile());
        System.out.println("Время выполнения: " + (System.currentTimeMillis() - timeStart) + " ms.");
    }

    /**
     * Парсинг вывода и сохранение в список
     * Список содержит два словаря для Transmit и Receive счетчиков
     */
    private static List<Map<String, Long>> outParser(String rawString) {

        Map<String, Long> transmit = new LinkedHashMap<>();
        Map<String, Long> receive = new LinkedHashMap<>();

        if (rawString == null) {
            return List.of(transmit, receive);
        }
        String[] tokens = rawString.split("\n");
        for (String s : tokens) {
            if (s.contains("sh ")) {
                continue;
            }
            if (s.contains("Transmit and Receive")) {
                break;
            }
            s = s.replaceAll("\\n", "").trim();
            String[] line = s.split("(  )+");
            if (line.length < 2) {
                continue;
            }
            String[] col1 = line[0].trim().split(" ", 2);
            String[] col2 = line[1].trim().split(" ", 2);
            if (col1.length > 1 && col2.length > 1) {
                Long t = null, r = null;
                if (!col1[0].isEmpty()) {
                    t = Long.parseLong(col1[0]);
                }
                if (!col2[0].isEmpty()) {
                    r = Long.parseLong(col2[0]);
                }
                transmit.put(col1[1], t);
                receive.put(col2[1], r);
            }
        }
        return List.of(transmit, receive);
    }

    /**
     * Подключается к удаленному коммутатору по telnet и выполняет удаленную команду
     * Полученный вывод возвращается как результат
     *
     * @param address IP адрес коммутатора
     * @param intNum  Номер интерфейса
     * @return Строка с выводом команды
     */
    static String getSwitchInfo(String address, int intNum, String password) throws IOException {
        TelnetClient telnet = new TelnetClient();
        telnet.setConnectTimeout(2000);
        try {
            telnet.connect(address);
        } catch (Exception e) {
            System.out.println("The switch " + address + " is not available");
            return null;
        }

        StringBuilder wholeBuffer = new StringBuilder();
        Expect expect = new ExpectBuilder()
                .withOutput(telnet.getOutputStream())
                .withInputs(telnet.getInputStream())
                .withEchoOutput(wholeBuffer)
                .withEchoInput(wholeBuffer)
                .withExceptionOnFailure()
                .build();

        expect.expect(contains("Password:"));
        expect.sendLine(password);
        expect.expect(contains(">"));
        expect.sendLine("terminal length 0");
        expect.expect(contains(">"));
        /* Get Interface status */
        expect.sendLine("sh controllers eth gi0/" + intNum + " | begin Transmit");
        wholeBuffer.setLength(0);
        expect.expect(contains(">"));
        expect.close();
        return wholeBuffer.toString();
    }

    /**
     * Формирование html страницы из полученных данных
     *
     * @param data Данные для формирования html
     */
    public static void createHtml(Map<String, List<List<Map<String, Long>>>> data, String htmlPathFile) {
        StringBuilder sb = new StringBuilder();
        String cssStyle;

        sb.append(headerFooter("header")).append("<h2>").append(new Date()).append("</h2>");
        String notes = "<h3>Примечания:</h3>" + LS
                + "<ul><li>Оранжевым цветом подсвечиваются счетчики с ошибками </li>" + LS
                + "<li>Счетчики с нулевыми значениями не отображаются</li>" + LS
                + "<li>Статистика работает корректно только с коммутаторами Cisco 2950</li>" + LS
                + "<li>Статистика основана на команде: <b>sh controllers ethernet-controller gi0/1(2)</b></li></ul>";
        sb.append(notes);
        for (String swName : data.keySet()) {
            for (int i = 0; i < 2; i++) {
                Map<String, Long> transmit = data.get(swName).get(i).get(0);
                Map<String, Long> receive = data.get(swName).get(i).get(1);
                if (i == 0) {
                    sb.append("<h2>").append(swName).append("</h2>");
                }
                sb.append("<table class=\"statTable\"><tbody><tr><td><h2>Port ").append(i + 1).append("</h2></td>");
                StringBuilder headers = new StringBuilder();
                StringBuilder values = new StringBuilder();
                // transmit
                for (String header : transmit.keySet()) {
                    Long transmitValue = transmit.get(header);
                    if (transmitValue > 0) {
                        headers.append("<td><b>").append(header).append("</b></td>");
                        cssStyle = !GOODCOUNTERS.contains(header) ? "statusWARN" : "statusOK";
                        values.append("<td class=\"").append(cssStyle).append("\">").append(transmitValue).append("</td>");
                    }
                }
                sb.append(headers);
                sb.append("</tr><tr><td><b>Transmit</b></td>").append(values).append("</tr>");
                // Receive
                headers.setLength(0);
                values.setLength(0);
                headers.append("<tr><td></td>");
                for (String header : receive.keySet()) {
                    Long receiveValue = receive.get(header);
                    if (receiveValue > 0) {
                        headers.append("<td><b>").append(header).append("</b></td>");
                        cssStyle = !GOODCOUNTERS.contains(header) ? "statusWARN" : "statusOK";
                        values.append("<td class=\"").append(cssStyle).append("\">").append(receiveValue).append("</td>");
                    }
                }
                sb.append(headers).append("</tr><tr><td><b>Receive</b></td>").append(values).append("</tr></tbody></table><span>&nbsp;</span>\n");
            }
        }
        sb.append(headerFooter("footer"));
        try (PrintWriter writer = new PrintWriter(htmlPathFile)) {
            writer.println(sb);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    public static String headerFooter(String html) {
        String header = "<html>" + LS
                + "<head><title>Interface counters</title><style>" + LS
                + "table.statTable {" + LS
                + "  border: 1px solid #1C6EA4;" + LS
                + "  background-color: #EEEEEE;" + LS
                + "  width: 100%;" + LS
                + "  text-align: left;" + LS
                + "  border-collapse: collapse;" + LS
                + "}" + LS
                + "table.statTable td, table.statTable th {" + LS
                + "  border: 1px solid #AAAAAA;" + LS
                + "  padding: 3px 2px;" + LS
                + "}" + LS
                + "table.statTable tbody td {" + LS
                + "  font-size: 13px;" + LS
                + "}" + LS
                + "table.statTable tr:nth-child(even) {" + LS
                + "  background: #D0E4F5;" + LS
                + "}" + LS
                + "table.statTable tfoot td {" + LS
                + "  font-size: 14px;" + LS
                + "}" + LS
                + "table.statTable tfoot .links {" + LS
                + "  text-align: right;" + LS
                + "}" + LS
                + "table.statTable tfoot .links a{" + LS
                + "  display: inline-block;" + LS
                + "  background: #1C6EA4;" + LS
                + "  color: #FFFFFF;" + LS
                + "  padding: 2px 8px;" + LS
                + "  border-radius: 5px;" + LS
                + "}" + LS
                + ".statusWARN { background-color: #FFBB33; }" + LS
                + ".statusOK { background-color: #D0E4F5; }" + LS
                + "</style></head><body>";
        String footer = "</body></html>";

        return "header".equals(html) ? header : footer;
    }
}
