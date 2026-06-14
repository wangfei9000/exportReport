package com.wf

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class Main {
    private static final String ACCOUNT_EXTERNAL_ID = '358f5eb811074b81998b10561af618b2'
    private static final URI VALUATION_URI = URI.create('https://dpi.yunfangdata.com/dpi/execute?code=CMBZPEKD4YPB8UHG')
    private static final URI REPORT_URI = URI.create('https://dpi.yunfangdata.com/dpi/execute?code=9WRUL5AS6MSO7QVT')
    private static final String DEFAULT_SOURCE_INPUT = 'abc.csv/Sheet1-表格 1.csv'
    private static final String DEFAULT_RESULT_CSV = 'outputs/abc_result.csv'
    private static final String DEFAULT_REPORT_DIR = 'report'
    private static final int REPORT_MAX_ATTEMPTS = 3
    private static final long REPORT_RETRY_DELAY_MILLIS = 5000
    private static final long REPORT_REQUEST_DELAY_MILLIS = 1000

    private static final Map<String, String> HOUSE_TYPE_MAP = [
            '普通商品用房': '住宅',
            '别墅'      : '别墅'
    ]

    // 程序入口，根据命令行参数选择估值或报告模式
    static void main_123(String[] args) {
        String mode = args.length > 0 ? args[0] : 'report'
        HttpClient httpClient = newHttpClient()

        switch (mode) {
            case 'valuation':
                runValuationMode(args, httpClient)
                return
            case 'report':
                runReportMode(args, httpClient)
                return
            default:
                throw new IllegalArgumentException("""
Unknown mode: ${mode}
Usage:
  ./gradlew run --args='valuation [sourceCsv] [resultCsv]'
  ./gradlew run --args='report [resultCsv] [reportDir]'
""".trim())
        }
    }

    // 估值模式：读取 CSV，逐行调用估值接口，输出结果 CSV
    private static void runValuationMode(String[] args, HttpClient httpClient) {
        Path inputCsv = Path.of(args.length > 1 ? args[1] : DEFAULT_SOURCE_INPUT)
        Path outputCsv = Path.of(args.length > 2 ? args[2] : DEFAULT_RESULT_CSV)
        ensureParentDirectory(outputCsv)

        List<CsvRow> rows = readRequiredRows(inputCsv)
        List<Map<String, String>> outputRows = runValuations(rows, httpClient)
        writeCsv(outputCsv, resultHeaders(rows), outputRows)
        println "估值完成：${outputCsv.toAbsolutePath()}"
    }

    // 报告模式：读取估值结果 CSV，生成报告并下载 PDF
    private static void runReportMode(String[] args, HttpClient httpClient) {
        Path inputCsv = Path.of(args.length > 1 ? args[1] : DEFAULT_RESULT_CSV)
        Path reportDir = Path.of(args.length > 2 ? args[2] : DEFAULT_REPORT_DIR)
        Files.createDirectories(reportDir)

        List<CsvRow> rows = readRequiredRows(inputCsv)
        List<Map<String, String>> outputRows = runReportsFromValuationJson(rows, httpClient, reportDir)
        writeCsv(inputCsv, resultHeaders(rows), outputRows)
        println "报告完成：${inputCsv.toAbsolutePath()}"
        println "PDF目录：${reportDir.toAbsolutePath()}"
    }

    // 逐行调用估值接口，收集估值 JSON 及处理状态
    private static List<Map<String, String>> runValuations(List<CsvRow> rows, HttpClient httpClient) {
        List<Map<String, String>> outputRows = []
        int rowNumber = 0

        rows.each { CsvRow csvRow ->
            rowNumber++
            Map<String, String> out = new LinkedHashMap<>(csvRow.values)
            String status = 'OK'
            String error = ''
            String valuationJson = ''

            try {
                String address = required(csvRow, '证载地址')
                String pledgeType = required(csvRow, '押品种类')
                String cityName = required(csvRow, '城市')
                String districtName = required(csvRow, '行政区域')
                BigDecimal area = parseArea(requiredAny(csvRow, ['建筑面积', '建筑面积（㎡）', '建筑面积(㎡)', '建筑面积（m²）']))
                String houseType = HOUSE_TYPE_MAP[pledgeType]
                if (!houseType) {
                    throw new IllegalArgumentException("Unsupported 押品种类: ${pledgeType}")
                }

                println "[${rowNumber}/${rows.size()}] 估值：${address}"
                Map valuationPayload = [
                        accountExternalId: ACCOUNT_EXTERNAL_ID,
                        cityName         : cityName,
                        districtName     : districtName,
                        address          : address,
                        area             : area,
                        houseType        : houseType,
                        mode             : '自动'
                ]
                Object valuation = postJson(httpClient, VALUATION_URI, valuationPayload)
                valuationJson = JsonOutput.toJson(valuation)

                if (!objectValue(valuation, 'externalId')) {
                    status = 'FAILED'
                    error = 'Valuation response does not contain externalId'
                }
            } catch (Exception ex) {
                status = 'FAILED'
                error = singleLine(ex.message ?: ex.toString())
                println "[${rowNumber}/${rows.size()}] 估值失败：${valueOf(csvRow, '证载地址') ?: "row-${rowNumber}"}，${error}"
            }

            out['估值返回JSON'] = valuationJson
            out['报告fileUrl'] = ''
            out['报告PDF文件'] = ''
            out['处理状态'] = status
            out['错误信息'] = error
            outputRows << out
        }

        return outputRows
    }

    // 创建 HTTP 客户端实例
    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
    }

    // 读取 CSV 并校验至少包含一行数据
    private static List<CsvRow> readRequiredRows(Path inputCsv) {
        List<CsvRow> rows = readCsv(inputCsv)
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV has no data rows: ${inputCsv}")
        }
        return rows
    }

    // 确保输出文件的父目录存在
    private static void ensureParentDirectory(Path path) {
        Path parent = path.parent
        if (parent) {
            Files.createDirectories(parent)
        }
    }

    // 根据估值 JSON 逐行生成报告并下载 PDF
    private static List<Map<String, String>> runReportsFromValuationJson(List<CsvRow> rows, HttpClient httpClient, Path reportDir) {
        List<Map<String, String>> outputRows = []
        int rowNumber = 0
        boolean reportInterfaceUnavailable = false
        String reportInterfaceError = ''

        rows.each { CsvRow csvRow ->
            rowNumber++
            Map<String, String> out = new LinkedHashMap<>(csvRow.values)
            String sequence = valueOf(csvRow, '序号') ?: rowNumber.toString()
            String address = valueOf(csvRow, '证载地址') ?: "row-${rowNumber}"
            String fileUrl = ''
            String pdfFile = ''
            String status = 'OK'
            String error = ''

            try {
                String valuationJson = required(csvRow, '估值返回JSON')
                Object valuation = new JsonSlurper().parseText(valuationJson)
                String externalId = objectValue(valuation, 'externalId')
                if (!externalId) {
                    throw new IllegalArgumentException('估值返回JSON does not contain externalId')
                }

                if (reportInterfaceUnavailable) {
                    status = 'FAILED'
                    error = reportInterfaceError
                } else {
                    println "[${rowNumber}/${rows.size()}] 生成报告：${externalId}"
                    Object report = postReport(httpClient, externalId)

                    fileUrl = objectValue(report, 'fileUrl')
                    if (!fileUrl) {
                        throw new IllegalArgumentException('Report response does not contain fileUrl')
                    }

                    String pdfFileName = sanitizeFileName("${sequence}${address}.pdf")
                    Path pdfPath = reportDir.resolve(pdfFileName)
                    downloadFile(httpClient, URI.create(fileUrl), pdfPath)
                    pdfFile = pdfPath.toString()
                    if (rowNumber < rows.size()) {
                        Thread.sleep(REPORT_REQUEST_DELAY_MILLIS)
                    }
                }
            } catch (Exception ex) {
                status = 'FAILED'
                error = singleLine(ex.message ?: ex.toString())
                if (ex instanceof HttpStatusException
                        && ((ex.statusCode == 405 && (ex.body ?: '').contains('METHOD_NOT_ALLOWED')) || ex.statusCode == 429)) {
                    reportInterfaceUnavailable = true
                    reportInterfaceError = error
                }
                println "[${rowNumber}/${rows.size()}] 报告失败：${address}，${error}"
            }

            out['报告fileUrl'] = fileUrl
            out['报告PDF文件'] = pdfFile
            out['处理状态'] = status
            out['错误信息'] = error
            outputRows << out
        }

        return outputRows
    }

    // 调用报告接口，429 限流时自动重试
    private static Object postReport(HttpClient httpClient, String externalId) {
        int attempt = 1
        while (true) {
            try {
                return postJson(httpClient, REPORT_URI, [
                        accountExternalId: ACCOUNT_EXTERNAL_ID,
                        estateId       : externalId,
                        reportTemplate : 'standard-R',
                        attachmentType : '评估报告'
                ])
            } catch (HttpStatusException ex) {
                if (ex.statusCode == 429 && attempt < REPORT_MAX_ATTEMPTS) {
                    long delay = REPORT_RETRY_DELAY_MILLIS * attempt
                    println "触发报告接口限流，等待 ${delay / 1000}s 后重试"
                    Thread.sleep(delay)
                    attempt++
                    continue
                }
                throw ex
            }
        }
    }

    // 构建输出 CSV 的表头，追加估值和报告相关列
    private static List<String> resultHeaders(List<CsvRow> rows) {
        List<String> headers = new ArrayList<>(rows.first().values.keySet())
        ['估值返回JSON', '报告fileUrl', '报告PDF文件', '处理状态', '错误信息'].each { header ->
            if (!headers.contains(header)) {
                headers << header
            }
        }
        return headers
    }

    // 发送 POST JSON 请求并解析响应
    private static Object postJson(HttpClient httpClient, URI uri, Map payload) {
        String body = JsonOutput.toJson(payload)
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(90))
                .header('Content-Type', 'application/json; charset=UTF-8')
                .header('Accept', 'application/json')
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new HttpStatusException(response.statusCode(), uri, response.body())
        }
        return new JsonSlurper().parseText(response.body())
    }

    // 下载文件到本地路径
    private static void downloadFile(HttpClient httpClient, URI uri, Path output) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .GET()
                .build()
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP ${response.statusCode()} download ${uri}")
        }
        Files.write(output, response.body())
    }

    // 读取 CSV 文件并解析为行对象列表
    private static List<CsvRow> readCsv(Path csvPath) {
        String csvText = Files.readString(csvPath, StandardCharsets.UTF_8)
        List<List<String>> records = parseCsvRecords(csvText)
        if (records.isEmpty()) {
            return []
        }

        List<String> headers = records.first()
        if (!headers.isEmpty()) {
            headers[0] = headers[0].replaceFirst('^\\uFEFF', '')
        }

        List<CsvRow> rows = []
        records.drop(1).each { values ->
            if (values.every { it == null || it.trim().isEmpty() }) {
                return
            }

            Map<String, String> row = new LinkedHashMap<>()
            headers.eachWithIndex { header, idx ->
                row[header] = idx < values.size() ? values[idx] : ''
            }
            if (isNonDataRow(row)) {
                return
            }
            rows << new CsvRow(row)
        }
        return rows
    }

    // 解析 CSV 文本为字段记录列表，支持引号和换行
    private static List<List<String>> parseCsvRecords(String text) {
        List<List<String>> records = []
        List<String> record = []
        StringBuilder current = new StringBuilder()
        boolean inQuotes = false

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i)
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    current.append(ch)
                }
            } else {
                if (ch == '"') {
                    inQuotes = true
                } else if (ch == ',') {
                    record << current.toString()
                    current.setLength(0)
                } else if (ch == '\n' || ch == '\r') {
                    if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++
                    }
                    record << current.toString()
                    current.setLength(0)
                    if (!record.every { it == null || it.trim().isEmpty() }) {
                        records << record
                    }
                    record = []
                } else {
                    current.append(ch)
                }
            }
        }

        if (current.length() > 0 || !record.isEmpty()) {
            record << current.toString()
            if (!record.every { it == null || it.trim().isEmpty() }) {
                records << record
            }
        }

        return records
    }

    // 判断是否为表头说明行（关键字段均为空）
    private static boolean isNonDataRow(Map<String, String> row) {
        return ['押品种类', '证载地址', '城市', '行政区域'].every { header ->
            !(row[header]?.trim())
        }
    }

    // 获取 CSV 行中指定列的值
    private static String valueOf(CsvRow row, String header) {
        return row.values[header]?.trim()
    }

    // 获取 CSV 行中必填列的值，缺失时抛出异常
    private static String required(CsvRow row, String header) {
        String value = valueOf(row, header)
        if (!value) {
            throw new IllegalArgumentException("Missing required column value: ${header}")
        }
        return value
    }

    // 从多个候选列名中获取第一个非空值
    private static String requiredAny(CsvRow row, List<String> headers) {
        for (String header : headers) {
            String value = valueOf(row, header)
            if (value) {
                return value
            }
        }

        String prefixMatch = row.values.find { key, value ->
            key?.startsWith('建筑面积') && value?.trim()
        }?.value
        if (prefixMatch) {
            return prefixMatch.trim()
        }

        throw new IllegalArgumentException("Missing required column value: ${headers.join('/')}")
    }

    // 解析建筑面积字符串为 BigDecimal
    private static BigDecimal parseArea(String raw) {
        String normalized = raw.replace(',', '').trim()
        if (!normalized) {
            throw new IllegalArgumentException('建筑面积为空')
        }
        return new BigDecimal(normalized)
    }

    // 从 Map 对象中安全获取指定键的字符串值
    private static String objectValue(Object object, String key) {
        if (object instanceof Map && object.containsKey(key) && object[key] != null) {
            return object[key].toString()
        }
        return ''
    }

    // 清理文件名中的非法字符并限制 UTF-8 字节长度
    private static String sanitizeFileName(String fileName) {
        String cleaned = fileName
                .replaceAll(/[\\/:*?"<>|]/, '_')
                .replaceAll(/[\\p{Cntrl}]/, '')
                .replaceAll(/\s+/, '')

        String suffix = '.pdf'
        String base = cleaned.toLowerCase().endsWith(suffix) ? cleaned.substring(0, cleaned.length() - suffix.length()) : cleaned
        while ((base + suffix).getBytes(StandardCharsets.UTF_8).length > 240 && base.length() > 1) {
            base = base.substring(0, base.length() - 1)
        }
        return base + suffix
    }

    // 将多行文本压缩为单行
    private static String singleLine(String text) {
        return (text ?: '').replaceAll(/[\r\n]+/, ' ').replaceAll(/\s+/, ' ').trim()
    }

    // 将数据写入 CSV 文件
    private static void writeCsv(Path csvPath, List<String> headers, List<Map<String, String>> rows) {
        List<String> lines = []
        lines << toCsvLine(headers)
        rows.each { row ->
            lines << toCsvLine(headers.collect { header -> row[header] ?: '' })
        }
        Files.write(csvPath, lines, StandardCharsets.UTF_8)
    }

    // 将字段列表转换为 CSV 行字符串
    private static String toCsvLine(List<String> values) {
        return values.collect { value ->
            String text = value ?: ''
            if (text.contains('"') || text.contains(',') || text.contains('\n') || text.contains('\r')) {
                return '"' + text.replace('"', '""') + '"'
            }
            return text
        }.join(',')
    }

    // CSV 行数据封装
    private static class CsvRow {
        final Map<String, String> values

        // 构造 CSV 行对象
        CsvRow(Map<String, String> values) {
            this.values = values
        }
    }

    // HTTP 非 2xx 响应异常
    private static class HttpStatusException extends IOException {
        final int statusCode
        final URI uri
        final String body

        // 构造 HTTP 状态异常
        HttpStatusException(int statusCode, URI uri, String body) {
            super("HTTP ${statusCode} ${uri}: ${body}")
            this.statusCode = statusCode
            this.uri = uri
            this.body = body
        }
    }
}
