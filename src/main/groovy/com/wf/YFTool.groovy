package com.wf

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import javax.swing.*
import javax.swing.plaf.basic.BasicTabbedPaneUI
import javax.swing.table.DefaultTableModel
import java.awt.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class YFTool {

    private static final String DEFAULT_API_LIST_FILE = 'apis.txt'
    private static final String SETTINGS_FILE = 'settings.txt'
    private static final String DEFAULT_CSV_FILE = 'abc.csv/Sheet1-表格 1.csv'
    private static final int CSV_PREVIEW_ROWS = 3
    private static final int CSV_TABLE_ROW_HEIGHT = 28
    private static final String BATCH_OUTPUT_DIR = 'outputs'
    private static final String PDF_OUTPUT_DIR = 'pdf'
    private static final String DEFAULT_SM4_HEX_KEY = '2b546becee09087b48e5a15a1654e775'
    private static final String DEFAULT_SM4_HTTP_URL = 'http://180.184.51.155:8080/dpi/execute?code=8TWnI8LKw92RXuhJ&accountExternalId=358f5eb811074b81998b10561af618b2'
    private static final int API_CALL_LOG_VERSION = 1
    private static final java.util.List<String> RESULT_COLUMN_PRIORITY = [
            'status', 'externalId', 'fileUrl', 'pdfPath', 'fileName', 'price', 'totalPrice', 'address', 'cityName', 'districtName', 'communityName'
    ]

    // 程序入口，在 EDT 线程中启动 GUI
    static void main(String[] args) {
        SwingUtilities.invokeLater { createAndShowGui() }
    }

    // 创建并显示估值工具窗口
    private static void createAndShowGui() {
        JFrame frame = new JFrame('云房')
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.setSize(1200, 600)
        frame.setLocationRelativeTo(null)

        Map<String, String> settings = loadSettings()
        AppContext context = new AppContext(frame: frame, settings: settings)
        context.httpClient = newHttpClient(context.connectTimeoutSeconds)

        JTabbedPane tabbedPane = createTabbedPane()
        tabbedPane.addTab('接口测试', createApiTestPanel(context))
        tabbedPane.addTab('SM4加密', createSm4Panel(context))
        tabbedPane.addTab('批量调用', createCsvPanel(context))
        tabbedPane.addTab('系统设置', createSettingsPanel(context))

        frame.layout = new BorderLayout()
        frame.add(tabbedPane, BorderLayout.CENTER)
        frame.visible = true

        context.reloadApiList.run()

        if (context.endpointCount == 0) {
            JOptionPane.showMessageDialog(frame,
                    "未加载到接口，请检查 ${resolveApiListPath(context.settings).toAbsolutePath()}",
                    '提示', JOptionPane.WARNING_MESSAGE)
        }
    }

    // 创建 Tab 面板，标签从左侧向右排列
    private static JTabbedPane createTabbedPane() {
        JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT)
        tabbedPane.font = new Font('PingFang SC', Font.PLAIN, 13)
        tabbedPane.setUI(new BasicTabbedPaneUI())
        return tabbedPane
    }

    // 创建接口测试页面
    private static JPanel createApiTestPanel(AppContext context) {
        JTextField urlField = new JTextField()
        context.urlField = urlField

        JButton requestButton = new JButton('请求接口')
        requestButton.preferredSize = new Dimension(100, 28)

        JPanel topPanel = new JPanel(new BorderLayout(8, 0))
        topPanel.border = BorderFactory.createEmptyBorder(12, 12, 8, 12)
        topPanel.add(urlField, BorderLayout.CENTER)
        topPanel.add(requestButton, BorderLayout.EAST)

        JTextArea jsonField = new JTextArea()
        jsonField.lineWrap = false
        jsonField.font = new Font('PingFang SC', Font.PLAIN, 13)
        context.jsonField = jsonField
        JScrollPane jsonScrollPane = new JScrollPane(jsonField)
        jsonScrollPane.border = BorderFactory.createTitledBorder('请求参数 (JSON)')

        JTextArea resultArea = new JTextArea()
        resultArea.editable = false
        resultArea.lineWrap = false
        resultArea.font = new Font('PingFang SC', Font.PLAIN, 13)
        context.resultArea = resultArea
        JScrollPane resultScrollPane = new JScrollPane(resultArea)
        resultScrollPane.border = BorderFactory.createTitledBorder('响应结果')

        JSplitPane contentSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, jsonScrollPane, resultScrollPane)
        contentSplitPane.resizeWeight = 0.35

        JPanel apiListPanel = createApiListPanel(context)
        context.apiListPanel = apiListPanel

        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, apiListPanel, contentSplitPane)
        mainSplitPane.resizeWeight = 0.0
        mainSplitPane.dividerLocation = 220
        mainSplitPane.border = BorderFactory.createEmptyBorder(0, 12, 12, 12)

        context.reloadApiList = {
            java.util.List<ApiEndpoint> endpoints = loadApiEndpointsFromTxt(context.settings)
            context.endpointCount = endpoints.size()
            context.apiListModel.clear()
            endpoints.each { context.apiListModel.addElement(it) }
            context.apiListPanel.putClientProperty('endpointCount', endpoints.size())
            if (!endpoints.isEmpty()) {
                context.apiList.selectedIndex = 0
            } else {
                urlField.text = ''
                jsonField.text = ''
            }
            context.reloadCsvApiCombo?.run()
        }

        bindRequestButton(context, requestButton)

        JPanel panel = new JPanel(new BorderLayout())
        panel.add(topPanel, BorderLayout.NORTH)
        panel.add(mainSplitPane, BorderLayout.CENTER)
        return panel
    }

    // 创建 SM4 加密请求页面
    private static JPanel createSm4Panel(AppContext context) {
        JComboBox<String> modeCombo = new JComboBox<>(['Hex(SM4Utils)', 'Base64(Hutool)'] as String[])
        modeCombo.font = new Font('PingFang SC', Font.PLAIN, 13)
        modeCombo.preferredSize = new Dimension(180, 28)
        String savedMode = context.settings.sm4Mode ?: Sm4Util.MODE_HEX
        modeCombo.selectedIndex = savedMode == Sm4Util.MODE_BASE64 ? 1 : 0
        context.sm4ModeCombo = modeCombo

        JTextField sm4KeyField = new JTextField(context.settings.sm4Key ?: DEFAULT_SM4_HEX_KEY, 40)
        sm4KeyField.font = new Font('PingFang SC', Font.PLAIN, 13)
        context.sm4KeyField = sm4KeyField

        JTextField urlField = new JTextField(context.settings.sm4HttpUrl ?: DEFAULT_SM4_HTTP_URL)
        urlField.font = new Font('PingFang SC', Font.PLAIN, 13)
        context.sm4UrlField = urlField

        JButton requestButton = new JButton('请求')
        requestButton.preferredSize = new Dimension(100, 28)

        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
        modePanel.border = BorderFactory.createEmptyBorder(12, 12, 4, 12)
        modePanel.add(new JLabel('加密模式:'))
        modePanel.add(modeCombo)

        JPanel keyPanel = new JPanel(new BorderLayout(8, 0))
        keyPanel.border = BorderFactory.createEmptyBorder(0, 12, 4, 12)
        keyPanel.add(new JLabel('SM4 Key:'), BorderLayout.WEST)
        keyPanel.add(sm4KeyField, BorderLayout.CENTER)

        JPanel urlPanel = new JPanel(new BorderLayout(8, 0))
        urlPanel.border = BorderFactory.createEmptyBorder(4, 12, 8, 12)
        urlPanel.add(new JLabel('接口 URL:'), BorderLayout.WEST)
        urlPanel.add(urlField, BorderLayout.CENTER)
        urlPanel.add(requestButton, BorderLayout.EAST)

        JTextArea jsonField = new JTextArea('{\n  \n}')
        jsonField.lineWrap = false
        jsonField.font = new Font('PingFang SC', Font.PLAIN, 13)
        context.sm4JsonField = jsonField
        JScrollPane jsonScrollPane = new JScrollPane(jsonField)
        jsonScrollPane.border = BorderFactory.createTitledBorder('请求参数 (JSON，发送前自动 SM4 加密)')

        JTextArea resultArea = new JTextArea()
        resultArea.editable = false
        resultArea.lineWrap = false
        resultArea.font = new Font('PingFang SC', Font.PLAIN, 13)
        context.sm4ResultArea = resultArea
        JScrollPane resultScrollPane = new JScrollPane(resultArea)
        resultScrollPane.border = BorderFactory.createTitledBorder('响应结果 (自动 SM4 解密)')

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, jsonScrollPane, resultScrollPane)
        splitPane.resizeWeight = 0.4
        splitPane.border = BorderFactory.createEmptyBorder(0, 12, 12, 12)

        JLabel hintLabel = new JLabel('<html>Hex：Key=32位hex，POST hex 密文；Base64：Key=16字节UTF-8，POST {"param":"..."}，响应取 param 解密</html>')
        hintLabel.font = new Font('PingFang SC', Font.PLAIN, 12)
        hintLabel.foreground = new Color(100, 100, 100)
        hintLabel.border = BorderFactory.createEmptyBorder(0, 12, 4, 12)

        bindSm4RequestButton(context, requestButton)

        JPanel topPanel = new JPanel()
        topPanel.layout = new BoxLayout(topPanel, BoxLayout.Y_AXIS)
        topPanel.add(modePanel)
        topPanel.add(keyPanel)
        topPanel.add(urlPanel)
        topPanel.add(hintLabel)

        JPanel panel = new JPanel(new BorderLayout())
        panel.add(topPanel, BorderLayout.NORTH)
        panel.add(splitPane, BorderLayout.CENTER)
        return panel
    }

    // 读取 SM4 页面当前加密模式
    private static String currentSm4Mode(AppContext context) {
        return context.sm4ModeCombo?.selectedIndex == 1 ? Sm4Util.MODE_BASE64 : Sm4Util.MODE_HEX
    }

    // 绑定 SM4 请求按钮：加密参数、POST 请求、解密响应
    private static void bindSm4RequestButton(AppContext context, JButton requestButton) {
        requestButton.addActionListener {
            String sm4Key = context.sm4KeyField.text?.trim()
            if (!sm4Key) {
                JOptionPane.showMessageDialog(context.frame, '请输入 SM4 Key', '提示', JOptionPane.WARNING_MESSAGE)
                return
            }

            String sm4Mode = currentSm4Mode(context)

            String url = context.sm4UrlField.text?.trim()
            if (!url) {
                JOptionPane.showMessageDialog(context.frame, '请输入接口 URL', '提示', JOptionPane.WARNING_MESSAGE)
                return
            }

            String plainJson = context.sm4JsonField.text?.trim()
            if (!plainJson) {
                JOptionPane.showMessageDialog(context.frame, '请输入 JSON 参数', '提示', JOptionPane.WARNING_MESSAGE)
                return
            }

            try {
                new JsonSlurper().parseText(plainJson)
            } catch (Exception e) {
                JOptionPane.showMessageDialog(context.frame, "JSON 格式错误: ${e.message}", '提示', JOptionPane.WARNING_MESSAGE)
                return
            }

            requestButton.enabled = false
            context.sm4ResultArea.text = "请求中: ${url}\n"

            int requestTimeout = context.requestTimeoutSeconds
            Thread.start {
                try {
                    EndpointCallResult result = postSm4Json(context.httpClient, url, plainJson,
                            sm4Mode, sm4Key, requestTimeout)
                    String displayText = formatSm4Result(result.statusCode, url, sm4Mode,
                            result.requestCipher, result.requestBody, result.rawBody,
                            result.responsePlainText, result.decryptError)
                    SwingUtilities.invokeLater {
                        showResult(context.sm4ResultArea, displayText)
                    }
                } catch (Exception e) {
                    String displayText = formatError(url, e)
                    SwingUtilities.invokeLater {
                        showResult(context.sm4ResultArea, displayText)
                    }
                } finally {
                    SwingUtilities.invokeLater {
                        requestButton.enabled = true
                    }
                }
            }
        }
    }

    // 格式化 SM4 请求响应：展示密文与解密结果
    private static String formatSm4Result(int statusCode, String url, String sm4Mode,
                                          String requestCipher, String requestBody,
                                          String rawBody, String responsePlainText,
                                          String decryptError) {
        StringBuilder sb = new StringBuilder()
        sb.append("状态码: ${statusCode}\n")
        sb.append("URL: ${url}\n")
        sb.append("模式: ${sm4Mode == Sm4Util.MODE_BASE64 ? 'SM4/ECB Base64 + JSON param' : 'SM4/ECB Hex(SM4Utils)'}\n\n")
        sb.append('--- 请求密文 ---\n')
        sb.append("${requestCipher}\n\n")
        sb.append('--- 请求体 ---\n')
        sb.append("${requestBody}\n\n")

        if (responsePlainText) {
            sb.append('--- 解密结果 ---\n')
            sb.append("${formatBody(responsePlainText)}\n\n")
        } else if (decryptError) {
            sb.append("--- 解密失败 ---\n${decryptError}\n\n")
        }

        sb.append('--- 原始响应 ---\n')
        sb.append(formatBody(rawBody))
        return decodeUnicodeEscapes(sb.toString())
    }

    // 按模式解密 SM4 响应
    private static String decryptSm4Response(String rawBody, String sm4Key, String sm4Mode) {
        String trimmed = rawBody?.trim()
        if (!trimmed) {
            return ''
        }

        if (sm4Mode == Sm4Util.MODE_BASE64) {
            String cipher = extractJsonParamField(trimmed)
            if (!cipher) {
                return ''
            }
            return Sm4Util.decryptBase64Ecb(cipher, sm4Key)
        }

        String cipherHex = extractSm4HexCipher(trimmed)
        if (!cipherHex) {
            return ''
        }
        return Sm4Util.decryptHexEcb(cipherHex, sm4Key)
    }

    // 从 JSON 响应中提取 param 字段（Hutool 模式）
    private static String extractJsonParamField(String responseBody) {
        try {
            Object parsed = new JsonSlurper().parseText(responseBody)
            if (parsed instanceof Map && parsed.param) {
                return parsed.param.toString()
            }
        } catch (ignored) {
        }
        return ''
    }

    // 从响应体中提取 SM4 hex 密文
    private static String extractSm4HexCipher(String responseBody) {
        String trimmed = responseBody?.trim()
        if (!trimmed) {
            return ''
        }
        if (isHexCipherText(trimmed)) {
            return trimmed
        }

        try {
            Object parsed = new JsonSlurper().parseText(trimmed)
            if (parsed instanceof String && isHexCipherText(parsed)) {
                return parsed
            }
            if (parsed instanceof Map) {
                for (String key in ['param', 'data', 'encryptData', 'content', 'result', 'body', 'response']) {
                    if (parsed[key] && isHexCipherText(parsed[key].toString())) {
                        return parsed[key].toString().trim()
                    }
                }
            }
        } catch (ignored) {
        }

        return ''
    }

    // 判断是否为 hex 密文
    private static boolean isHexCipherText(String text) {
        String trimmed = text?.trim()
        return trimmed && trimmed.length() >= 2 && trimmed.length() % 2 == 0 && trimmed ==~ /(?i)[0-9a-f]+/
    }

    // 创建 CSV 批量调用页面
    private static JPanel createCsvPanel(AppContext context) {
        CsvPanelContext csvContext = new CsvPanelContext()
        context.csvPanelContext = csvContext

        JTextField filePathField = new JTextField(DEFAULT_CSV_FILE)
        csvContext.filePathField = filePathField
        JButton browseButton = new JButton('选择文件')
        browseButton.preferredSize = new Dimension(100, 28)
        JButton loadButton = new JButton('读取')
        loadButton.preferredSize = new Dimension(80, 28)

        JPanel topPanel = new JPanel(new BorderLayout(8, 0))
        topPanel.border = BorderFactory.createEmptyBorder(12, 12, 8, 12)
        topPanel.add(filePathField, BorderLayout.CENTER)

        JPanel fileButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0))
        fileButtonPanel.add(browseButton)
        fileButtonPanel.add(loadButton)
        topPanel.add(fileButtonPanel, BorderLayout.EAST)

        JTable csvTable = new JTable()
        csvTable.font = new Font('PingFang SC', Font.PLAIN, 13)
        csvTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        csvTable.rowHeight = CSV_TABLE_ROW_HEIGHT
        JScrollPane previewScrollPane = new JScrollPane(csvTable)
        previewScrollPane.border = BorderFactory.createTitledBorder('数据源（CSV）')
        previewScrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        previewScrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        int previewPanelHeight = CSV_TABLE_ROW_HEIGHT * CSV_PREVIEW_ROWS + 54
        previewScrollPane.preferredSize = new Dimension(0, previewPanelHeight)
        csvContext.previewTable = csvTable
        csvContext.previewScrollPane = previewScrollPane
        csvContext.sourceMode = 'csv'

        JComboBox<ApiEndpoint> apiCombo = new JComboBox<>()
        apiCombo.font = new Font('PingFang SC', Font.PLAIN, 13)
        apiCombo.preferredSize = new Dimension(280, 28)
        csvContext.apiCombo = apiCombo

        JPanel apiSelectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4))
        apiSelectPanel.border = BorderFactory.createEmptyBorder(0, 12, 4, 12)
        apiSelectPanel.add(new JLabel('选择接口:'))
        apiSelectPanel.add(apiCombo)

        JPanel mappingPanel = new JPanel(new GridBagLayout())
        mappingPanel.border = BorderFactory.createTitledBorder('参数映射（{{字段}} 读取 CSV，否则为固定值）')
        csvContext.mappingPanel = mappingPanel
        csvContext.paramValueFields = [:]

        JButton testButton = new JButton('测试')
        testButton.preferredSize = new Dimension(100, 28)
        JButton batchButton = new JButton('批量调用')
        batchButton.preferredSize = new Dimension(100, 28)
        csvContext.batchButton = batchButton
        JButton stopButton = new JButton('停止')
        stopButton.preferredSize = new Dimension(80, 28)
        stopButton.enabled = false
        csvContext.stopButton = stopButton

        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8))
        actionPanel.border = BorderFactory.createEmptyBorder(0, 12, 4, 12)
        actionPanel.add(testButton)
        actionPanel.add(batchButton)
        actionPanel.add(stopButton)

        JTable resultTable = new JTable()
        resultTable.font = new Font('PingFang SC', Font.PLAIN, 13)
        resultTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        resultTable.rowHeight = 28
        JScrollPane resultTableScrollPane = new JScrollPane(resultTable)
        JPanel resultPanel = new JPanel(new BorderLayout())
        resultPanel.border = BorderFactory.createTitledBorder('接口返回')
        resultPanel.add(resultTableScrollPane, BorderLayout.CENTER)
        int resultPanelHeight = 220
        resultPanel.preferredSize = new Dimension(0, resultPanelHeight)
        resultPanel.maximumSize = new Dimension(Integer.MAX_VALUE, resultPanelHeight)
        csvContext.resultTable = resultTable

        JLabel infoLabel = new JLabel(' ')
        infoLabel.font = new Font('PingFang SC', Font.PLAIN, 12)
        infoLabel.foreground = new Color(100, 100, 100)
        infoLabel.border = BorderFactory.createEmptyBorder(0, 12, 8, 12)
        csvContext.infoLabel = infoLabel

        Runnable reloadApiCombo = {
            ApiEndpoint selected = apiCombo.selectedItem as ApiEndpoint
            apiCombo.removeAllItems()

            java.util.List<ApiEndpoint> endpoints = loadApiEndpointsFromTxt(context.settings)
            endpoints.each { apiCombo.addItem(it) }

            if (selected) {
                (0..<apiCombo.itemCount).each { idx ->
                    if (apiCombo.getItemAt(idx)?.name == selected.name) {
                        apiCombo.selectedIndex = idx
                        return
                    }
                }
            }
            if (apiCombo.selectedIndex < 0 && apiCombo.itemCount > 0) {
                apiCombo.selectedIndex = 0
            }
            refreshParamMappingTable(csvContext)
        }
        context.reloadCsvApiCombo = reloadApiCombo

        Runnable loadSource = {
            loadSourceFile(context, csvContext, null)
        }

        apiCombo.addActionListener { refreshParamMappingTable(csvContext) }

        browseButton.addActionListener {
            JFileChooser chooser = new JFileChooser()
            chooser.fileSelectionMode = JFileChooser.FILES_ONLY
            String currentPath = filePathField.text?.trim()
            if (currentPath) {
                Path path = Path.of(currentPath)
                if (Files.exists(path)) {
                    chooser.selectedFile = path.toFile()
                } else if (path.parent) {
                    chooser.currentDirectory = path.parent.toFile()
                }
            }
            if (chooser.showOpenDialog(context.frame) == JFileChooser.APPROVE_OPTION) {
                filePathField.text = chooser.selectedFile.absolutePath
                loadSource.run()
            }
        }

        loadButton.addActionListener(loadSource)
        stopButton.addActionListener { requestBatchStop(csvContext) }
        testButton.addActionListener { runCsvTestCall(context, csvContext, testButton) }
        batchButton.addActionListener { runCsvBatchCall(context, csvContext, batchButton) }

        JPanel scrollContent = new JPanel()
        scrollContent.layout = new BoxLayout(scrollContent, BoxLayout.Y_AXIS)
        scrollContent.border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
        scrollContent.add(previewScrollPane)
        scrollContent.add(apiSelectPanel)
        scrollContent.add(mappingPanel)
        scrollContent.add(actionPanel)
        scrollContent.add(resultPanel)
        scrollContent.add(infoLabel)

        [previewScrollPane, apiSelectPanel, mappingPanel, actionPanel, resultPanel, infoLabel].each { component ->
            component.alignmentX = Component.LEFT_ALIGNMENT
        }
        previewScrollPane.maximumSize = new Dimension(Integer.MAX_VALUE, previewPanelHeight)
        resultPanel.maximumSize = new Dimension(Integer.MAX_VALUE, resultPanelHeight)
        adjustMappingPanelHeight(csvContext)

        JScrollPane pageScrollPane = new JScrollPane(scrollContent)
        pageScrollPane.border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
        pageScrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        pageScrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        pageScrollPane.getVerticalScrollBar().unitIncrement = 16

        JPanel panel = new JPanel(new BorderLayout())
        panel.add(topPanel, BorderLayout.NORTH)
        panel.add(pageScrollPane, BorderLayout.CENTER)

        reloadApiCombo.run()
        if (Files.exists(Path.of(DEFAULT_CSV_FILE))) {
            SwingUtilities.invokeLater(loadSource)
        }

        return panel
    }

    // 根据文件后缀加载数据源：.csv 或 .log
    private static void loadSourceFile(AppContext context, CsvPanelContext csvContext, Path sourcePath) {
        try {
            Path path = sourcePath
            if (path == null) {
                String pathText = csvContext.filePathField.text?.trim()
                if (!pathText) {
                    JOptionPane.showMessageDialog(context.frame, '请输入文件路径', '提示', JOptionPane.WARNING_MESSAGE)
                    return
                }
                path = Path.of(pathText)
            }

            if (!Files.exists(path)) {
                throw new FileNotFoundException("文件不存在: ${path.toAbsolutePath()}")
            }

            csvContext.filePathField.text = path.toString()
            if (isLogFile(path)) {
                loadLogSourceFile(context, csvContext, path)
            } else if (isCsvFile(path)) {
                loadCsvDataset(context, csvContext, path)
            } else {
                throw new IllegalArgumentException('仅支持 .csv 或 .log 文件')
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(context.frame,
                    "读取文件失败:\n${e.message}", '提示', JOptionPane.ERROR_MESSAGE)
        }
    }

    // 判断是否为日志文件
    private static boolean isLogFile(Path path) {
        return path.fileName.toString().toLowerCase().endsWith('.log')
    }

    // 判断是否为 CSV 文件
    private static boolean isCsvFile(Path path) {
        return path.fileName.toString().toLowerCase().endsWith('.csv')
    }

    // 加载 .log 日志到第一个表格，数据缓存在 sourceRows
    private static void loadLogSourceFile(AppContext context, CsvPanelContext csvContext, Path logPath) {
        ApiCallLogFile logFile = parseApiCallLog(logPath)
        csvContext.sourceMode = 'log'
        csvContext.sourceHeaders = []
        csvContext.sourceRows = new ArrayList<>(logFile.records)
        csvContext.resultRecords = []
        showLogRecordsInSourceTable(csvContext.previewTable, csvContext.sourceRows)
        updateSourcePanelAppearance(csvContext)
        clearResultTable(csvContext.resultTable)
        refreshParamMappingTable(csvContext)
        csvContext.infoLabel.text = "日志数据源: ${logPath.toAbsolutePath()}，共 ${csvContext.sourceRows.size()} 条（已缓存）"
    }

    // 执行单次报告调用，estateId 取自估值结果 externalId
    private static ApiCallLogRecord executeReportCall(AppContext context, ApiEndpoint reportEndpoint,
                                                      ApiCallLogRecord valuationRecord, int requestTimeout) {
        Map payload = null
        try {
            String externalId = extractExternalId(valuationRecord)
            if (!externalId) {
                throw new IllegalArgumentException('估值结果缺少 externalId')
            }

            payload = buildReportPayload(reportEndpoint, externalId)
            EndpointCallResult callResult = postEndpointPayload(context, reportEndpoint, payload, requestTimeout)
            return new ApiCallLogRecord(
                    rowNumber: valuationRecord.rowNumber,
                    statusCode: callResult.statusCode,
                    url: reportEndpoint.url,
                    request: payload,
                    response: callResult.parsedBody,
                    error: sm4DecryptErrorMessage(callResult)
            )
        } catch (Exception e) {
            return new ApiCallLogRecord(
                    rowNumber: valuationRecord.rowNumber,
                    statusCode: 0,
                    url: reportEndpoint.url,
                    request: payload,
                    response: null,
                    error: e.message
            )
        }
    }

    // 从估值记录中提取 externalId
    private static String extractExternalId(ApiCallLogRecord valuationRecord) {
        if (valuationRecord.response instanceof Map) {
            Object externalId = valuationRecord.response.externalId
            return externalId?.toString()?.trim()
        }
        return null
    }

    // 构建报告请求参数
    private static Map buildReportPayload(ApiEndpoint reportEndpoint, String externalId) {
        Object template = new JsonSlurper().parseText(reportEndpoint.params)
        if (!(template instanceof Map)) {
            throw new IllegalArgumentException('报告接口参数模板必须是 JSON 对象')
        }

        Map payload = new LinkedHashMap<>(template)
        payload.estateId = externalId
        return payload
    }

    // 读取 CSV 全量数据，缓存到 sourceRows 并刷新第一个表格
    private static void loadCsvDataset(AppContext context, CsvPanelContext csvContext, Path csvPath) {
        try {
            CsvDataset dataset = readCsvFile(csvPath)
            csvContext.sourceMode = 'csv'
            csvContext.sourceHeaders = new ArrayList<>(dataset.headers)
            csvContext.sourceRows = new ArrayList<>(dataset.rows)
            csvContext.resultRecords = []
            CsvPreview preview = toCsvPreview(dataset, CSV_PREVIEW_ROWS)
            updateCsvTable(csvContext.previewTable, preview)
            updateSourcePanelAppearance(csvContext)
            refreshParamMappingTable(csvContext)
            clearResultTable(csvContext.resultTable)
            csvContext.infoLabel.text = "CSV 数据源: ${csvPath.toAbsolutePath()}    共 ${csvContext.sourceRows.size()} 行（已缓存）    预览前 ${preview.rows.size()} 行"
        } catch (Exception e) {
            csvContext.sourceHeaders = []
            csvContext.sourceRows = []
            updateCsvTable(csvContext.previewTable, new CsvPreview([], []))
            csvContext.infoLabel.text = '读取失败'
            throw e
        }
    }

    // 刷新参数映射区域（4 列布局：参数名 + 参数值，每行 2 组）
    private static void refreshParamMappingTable(CsvPanelContext csvContext) {
        JPanel panel = csvContext.mappingPanel
        panel.removeAll()
        csvContext.paramValueFields = [:]

        ApiEndpoint endpoint = csvContext.apiCombo.selectedItem as ApiEndpoint
        if (!endpoint) {
            adjustMappingPanelHeight(csvContext)
            return
        }

        Object template = new JsonSlurper().parseText(endpoint.params)
        if (!(template instanceof Map)) {
            adjustMappingPanelHeight(csvContext)
            return
        }

        java.util.List<String> paramNames = new ArrayList<>(template.keySet()*.toString())
        java.util.List<String> headers = csvContext.sourceHeaders ?: []

        GridBagConstraints labelConstraints = new GridBagConstraints(
                anchor: GridBagConstraints.EAST,
                insets: new Insets(4, 8, 4, 4)
        )
        GridBagConstraints fieldConstraints = new GridBagConstraints(
                fill: GridBagConstraints.HORIZONTAL,
                weightx: 1.0,
                insets: new Insets(4, 4, 4, 8)
        )

        paramNames.eachWithIndex { String paramName, int index ->
            int gridy = index.intdiv(2)
            int gridxLabel = (index % 2) * 2
            int gridxField = gridxLabel + 1

            labelConstraints.gridx = gridxLabel
            labelConstraints.gridy = gridy
            fieldConstraints.gridx = gridxField
            fieldConstraints.gridy = gridy

            JLabel label = new JLabel("${paramName}:")
            label.font = new Font('PingFang SC', Font.PLAIN, 13)
            JTextField field = new JTextField(formatDefaultParamValue(paramName, template[paramName], headers))
            field.font = new Font('PingFang SC', Font.PLAIN, 13)
            csvContext.paramValueFields[paramName] = field

            panel.add(label, labelConstraints)
            panel.add(field, fieldConstraints)
        }

        panel.revalidate()
        panel.repaint()
        adjustMappingPanelHeight(csvContext)
    }

    // 生成参数默认值：匹配 CSV 列用 {{列名}}，否则用模板固定值
    private static String formatDefaultParamValue(String paramName, Object templateValue, java.util.List<String> headers) {
        String column = guessCsvColumn(paramName, headers)
        if (column) {
            return "{{${column}}}"
        }
        return templateValue == null ? '' : templateValue.toString()
    }

    // 根据参数行数自动调整映射区域高度
    private static void adjustMappingPanelHeight(CsvPanelContext csvContext) {
        JPanel panel = csvContext.mappingPanel
        if (!panel) {
            return
        }

        int paramCount = csvContext.paramValueFields?.size() ?: 0
        int rowCount = Math.max((paramCount + 1).intdiv(2), 1)
        int panelHeight = rowCount * 36 + 16
        panel.preferredSize = new Dimension(0, panelHeight)
        panel.maximumSize = new Dimension(Integer.MAX_VALUE, panelHeight)
        panel.revalidate()
        panel.parent?.revalidate()
    }

    // 根据参数名猜测对应的 CSV 列
    private static String guessCsvColumn(String paramName, java.util.List<String> headers) {
        Map<String, java.util.List<String>> aliases = [
                cityName         : ['城市'],
                districtName     : ['行政区域'],
                address          : ['证载地址'],
                area             : ['建筑面积', '建筑面积（㎡）', '建筑面积(㎡)', '建筑面积（m²）'],
                //houseType        : ['押品种类', '房屋类型'],
                mode             : ['模式'],
                estateId         : ['externalId', 'estateId'],
                pdfUrl           : ['fileUrl', 'url', '报告fileUrl'],
                fileName         : ['fileName', '报告PDF文件'],
        ]

        if (aliases.containsKey(paramName)) {
            for (String alias : aliases[paramName]) {
                if (headers.contains(alias)) {
                    return alias
                }
            }
        }

        return headers.find { it == paramName } ?: headers.find { it.equalsIgnoreCase(paramName) }
    }

    // 判断是否为下载报告接口
    private static boolean isDownloadReportEndpoint(ApiEndpoint endpoint) {
        return endpoint?.name?.contains('批量下载报告')
    }

    // 判断是否为生成报告类接口（排除下载报告）
    private static boolean isReportEndpoint(ApiEndpoint endpoint) {
        return endpoint?.name?.contains('批量生成报告') && !isDownloadReportEndpoint(endpoint)
    }

    // 将日志记录展平为字段 Map，供参数模板替换
    private static Map<String, String> flattenLogRecordFields(ApiCallLogRecord record) {
        Map<String, String> fields = new LinkedHashMap<>()
        fields.row = record.rowNumber?.toString() ?: ''
        fields.id = fields.row

        if (record.request instanceof Map) {
            (record.request as Map).each { key, value ->
                fields[key.toString()] = value?.toString() ?: ''
            }
        }
        if (record.response instanceof Map) {
            (record.response as Map).each { key, value ->
                fields[key.toString()] = value?.toString() ?: ''
            }
        }
        if (fields.fileUrl && !fields.url) {
            fields.url = fields.fileUrl
        }
        return fields
    }

    // 替换模板中的 {{字段}} 占位符
    private static String resolveFieldTemplate(String template, Map<String, String> fields) {
        if (!template) {
            return ''
        }
        return template.replaceAll(/\{\{(.+?)\}\}/) { _, fieldName ->
            String key = fieldName.trim()
            if (!fields.containsKey(key)) {
                throw new IllegalArgumentException("字段不存在: ${key}")
            }
            fields[key] ?: ''
        }
    }

    // 解析映射值：支持 {{字段}} 及含多个占位符的文件名模板
    private static String resolveMappingValue(String valueTemplate, Map<String, String> fields, Object templateValue) {
        String trimmed = valueTemplate?.trim()
        if (!trimmed) {
            return templateValue?.toString()?.trim() ?: ''
        }
        if (trimmed.contains('{{')) {
            return resolveFieldTemplate(trimmed, fields)
        }
        Object resolved = resolveParamValue(trimmed, fields, templateValue)
        return resolved?.toString()?.trim() ?: ''
    }

    // 下载报告：用 pdfUrl 直接 GET，保存到 ./pdf/{fileName}.pdf
    private static ApiCallLogRecord executeDownloadReportCall(AppContext context, ApiEndpoint endpoint,
                                                              Map<String, String> fieldMap, int rowNumber,
                                                              Map<String, String> mapping, int requestTimeout) {
        Map requestMeta = [:]
        try {
            Object templateObj = new JsonSlurper().parseText(endpoint.params)
            if (!(templateObj instanceof Map)) {
                throw new IllegalArgumentException('下载报告参数模板必须是 JSON 对象')
            }
            Map template = templateObj as Map

            String pdfUrl = resolveMappingValue(mapping.pdfUrl, fieldMap, template.pdfUrl)
            String fileName = resolveMappingValue(mapping.fileName, fieldMap, template.fileName)
            if (!pdfUrl) {
                throw new IllegalArgumentException('pdfUrl 不能为空')
            }
            if (!fileName) {
                fileName = "row-${rowNumber}.pdf"
            }

            Path pdfDir = Path.of(System.getProperty('user.dir')).resolve(PDF_OUTPUT_DIR)
            Files.createDirectories(pdfDir)
            String safeName = sanitizePdfFileName(fileName)
            Path outputPath = pdfDir.resolve(safeName)

            requestMeta = [pdfUrl: pdfUrl, fileName: safeName, outputPath: outputPath.toString()]
            downloadPdfFile(context.httpClient, URI.create(pdfUrl), outputPath, requestTimeout)

            return new ApiCallLogRecord(
                    rowNumber: rowNumber,
                    statusCode: 200,
                    url: pdfUrl,
                    request: requestMeta,
                    response: [pdfPath: outputPath.toAbsolutePath().toString(), fileName: safeName],
                    error: null
            )
        } catch (Exception e) {
            return new ApiCallLogRecord(
                    rowNumber: rowNumber,
                    statusCode: 0,
                    url: requestMeta.pdfUrl ?: endpoint.url,
                    request: requestMeta,
                    response: null,
                    error: e.message
            )
        }
    }

    // GET 下载 PDF 文件
    private static void downloadPdfFile(HttpClient httpClient, URI uri, Path outputPath, int requestTimeout) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(requestTimeout))
                .GET()
                .build()
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP ${response.statusCode()} 下载失败: ${uri}")
        }
        Files.write(outputPath, response.body())
    }

    // 清理 PDF 文件名非法字符
    private static String sanitizePdfFileName(String fileName) {
        String cleaned = fileName
                .replaceAll(/[\\/:*?"<>|]/, '_')
                .replaceAll(/[\p{Cntrl}]/, '')
                .replaceAll(/\s+/, '')

        String suffix = '.pdf'
        String base = cleaned.toLowerCase().endsWith(suffix)
                ? cleaned.substring(0, cleaned.length() - suffix.length())
                : cleaned
        while ((base + suffix).getBytes(StandardCharsets.UTF_8).length > 240 && base.length() > 1) {
            base = base.substring(0, base.length() - 1)
        }
        return base + suffix
    }

    // 单条数据源调用：按接口类型分发
    private static ApiCallLogRecord executeSourceRowCall(AppContext context, CsvPanelContext csvContext,
                                                         ApiEndpoint endpoint, Object row, int rowNumber,
                                                         Map<String, String> mapping, int requestTimeout) {
        if (csvContext.sourceMode == 'log') {
            ApiCallLogRecord sourceRecord = row as ApiCallLogRecord
            if (isDownloadReportEndpoint(endpoint)) {
                return executeDownloadReportCall(context, endpoint, flattenLogRecordFields(sourceRecord),
                        sourceRecord.rowNumber, mapping, requestTimeout)
            }
            if (isReportEndpoint(endpoint)) {
                return executeReportCall(context, endpoint, sourceRecord, requestTimeout)
            }
            return executeApiCallWithPayload(context, endpoint, sourceRecord.request as Map,
                    sourceRecord.rowNumber, requestTimeout)
        }

        Map<String, String> csvRow = row as Map<String, String>
        if (isDownloadReportEndpoint(endpoint)) {
            return executeDownloadReportCall(context, endpoint, csvRow, rowNumber, mapping, requestTimeout)
        }
        return executeApiCall(context, endpoint, csvRow, mapping, rowNumber, requestTimeout)
    }

    // 日志数据源下单条调用（兼容旧调用）
    private static ApiCallLogRecord executeLogSourceCall(AppContext context, CsvPanelContext csvContext,
                                                         ApiEndpoint endpoint, ApiCallLogRecord sourceRecord,
                                                         Map<String, String> mapping, int requestTimeout) {
        return executeSourceRowCall(context, csvContext, endpoint, sourceRecord, sourceRecord.rowNumber,
                mapping, requestTimeout)
    }

    // 测试调用，取 sourceRows 第一条
    private static void runCsvTestCall(AppContext context, CsvPanelContext csvContext, JButton testButton) {
        ApiEndpoint endpoint = validateSourceCall(context, csvContext)
        if (!endpoint) {
            return
        }

        testButton.enabled = false
        clearResultTable(csvContext.resultTable)

        int requestTimeout = context.requestTimeoutSeconds
        Object firstRow = csvContext.sourceRows.first()
        Map<String, String> mapping = readParamMapping(csvContext)
        Thread.start {
            try {
                ApiCallLogRecord record = executeSourceRowCall(context, csvContext, endpoint, firstRow, 1,
                        mapping, requestTimeout)
                java.util.List<ApiCallLogRecord> records = [record]
                Path logPath = writeApiCallLog(endpoint, records)
                String output = logPath.toAbsolutePath().toString()
                println "测试调用完成：${output}"

                SwingUtilities.invokeLater {
                    csvContext.resultRecords = records
                    showApiResponseInTable(csvContext.resultTable, records)
                    csvContext.infoLabel.text = "测试完成，接口返回 ${records.size()} 条，日志: ${output}"
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater {
                    csvContext.infoLabel.text = "测试失败: ${e.message}"
                    JOptionPane.showMessageDialog(context.frame,
                            "测试失败:\n${e.message}", '提示', JOptionPane.ERROR_MESSAGE)
                }
            } finally {
                SwingUtilities.invokeLater {
                    testButton.enabled = true
                }
            }
        }
    }

    // 批量调用，循环 sourceRows 数组（不再读文件）
    private static void runCsvBatchCall(AppContext context, CsvPanelContext csvContext, JButton batchButton) {
        ApiEndpoint endpoint = validateSourceCall(context, csvContext)
        if (!endpoint) {
            return
        }

        prepareBatchRun(csvContext, csvContext.batchButton)
        clearResultTable(csvContext.resultTable)
        java.util.List sourceRows = new ArrayList<>(csvContext.sourceRows)
        csvContext.infoLabel.text = "批量调用中: ${endpoint.name}，共 ${sourceRows.size()} 条..."

        int requestTimeout = context.requestTimeoutSeconds
        Thread.start {
            boolean stopped = false
            try {
                Map<String, String> mapping = readParamMapping(csvContext)
                java.util.List<ApiCallLogRecord> records = []

                for (int index = 0; index < sourceRows.size(); index++) {
                    if (shouldStopBatch(csvContext)) {
                        stopped = true
                        break
                    }
                    Object row = sourceRows[index]
                    int rowNumber = csvContext.sourceMode == 'log'
                            ? (row as ApiCallLogRecord).rowNumber
                            : index + 1
                    records << executeSourceRowCall(context, csvContext, endpoint, row, rowNumber,
                            mapping, requestTimeout)
                }

                Path logPath = writeApiCallLog(endpoint, records)
                String output = logPath.toAbsolutePath().toString()
                println stopped ? "批量调用已停止：${output}" : "批量调用完成：${output}"

                int total = sourceRows.size()
                SwingUtilities.invokeLater {
                    csvContext.resultRecords = records
                    showApiResponseInTable(csvContext.resultTable, records)
                    csvContext.infoLabel.text = stopped
                            ? "批量已停止，完成 ${records.size()}/${total} 条，日志: ${output}"
                            : "批量完成，接口返回 ${records.size()} 条，日志: ${output}"
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater {
                    csvContext.infoLabel.text = "批量调用失败: ${e.message}"
                    JOptionPane.showMessageDialog(context.frame,
                            "批量调用失败:\n${e.message}", '提示', JOptionPane.ERROR_MESSAGE)
                }
            } finally {
                SwingUtilities.invokeLater {
                    finishBatchRun(csvContext, csvContext.batchButton)
                }
            }
        }
    }

    // 标记请求停止批量任务
    private static void requestBatchStop(CsvPanelContext csvContext) {
        csvContext.batchStopRequested = true
        csvContext.stopButton.enabled = false
        csvContext.infoLabel.text = '正在停止，请等待当前请求完成...'
    }

    // 批量任务开始前重置停止状态
    private static void prepareBatchRun(CsvPanelContext csvContext, JButton... runningButtons) {
        csvContext.batchStopRequested = false
        csvContext.stopButton.enabled = true
        runningButtons.each { it.enabled = false }
    }

    // 批量任务结束后恢复按钮状态
    private static void finishBatchRun(CsvPanelContext csvContext, JButton... runningButtons) {
        csvContext.batchStopRequested = false
        csvContext.stopButton.enabled = false
        runningButtons.each { it.enabled = true }
    }

    // 每次循环前检查是否已请求停止
    private static boolean shouldStopBatch(CsvPanelContext csvContext) {
        return csvContext.batchStopRequested
    }

    // 使用已有 request 体发起接口调用（日志数据源重放）
    private static ApiCallLogRecord executeApiCallWithPayload(AppContext context, ApiEndpoint endpoint, Map payload,
                                                              int rowNumber, int requestTimeout) {
        try {
            EndpointCallResult callResult = postEndpointPayload(context, endpoint, payload, requestTimeout)
            return new ApiCallLogRecord(
                    rowNumber: rowNumber,
                    statusCode: callResult.statusCode,
                    url: endpoint.url,
                    request: payload,
                    response: callResult.parsedBody,
                    error: sm4DecryptErrorMessage(callResult)
            )
        } catch (Exception e) {
            return new ApiCallLogRecord(
                    rowNumber: rowNumber,
                    statusCode: 0,
                    url: endpoint.url,
                    request: payload,
                    response: null,
                    error: e.message
            )
        }
    }

    // 执行单次接口调用
    private static ApiCallLogRecord executeApiCall(AppContext context, ApiEndpoint endpoint, Map<String, String> csvRow,
                                                   Map<String, String> mapping, int rowNumber, int requestTimeout) {
        Map payload = null
        try {
            payload = buildPayloadFromCsvRow(csvRow, endpoint, mapping)
            EndpointCallResult callResult = postEndpointPayload(context, endpoint, payload, requestTimeout)
            return new ApiCallLogRecord(
                    rowNumber: rowNumber,
                    statusCode: callResult.statusCode,
                    url: endpoint.url,
                    request: payload,
                    response: callResult.parsedBody,
                    error: sm4DecryptErrorMessage(callResult)
            )
        } catch (Exception e) {
            return new ApiCallLogRecord(
                    rowNumber: rowNumber,
                    statusCode: 0,
                    url: endpoint.url,
                    request: payload,
                    response: null,
                    error: e.message
            )
        }
    }

    // 按接口配置发送请求，普通接口发 JSON，SM4 接口发加密密文
    private static EndpointCallResult postEndpointPayload(AppContext context, ApiEndpoint endpoint, Map payload,
                                                          int requestTimeout) {
        return postEndpointJson(context, endpoint, endpoint.url, JsonOutput.toJson(payload), requestTimeout)
    }

    // 按接口配置发送 JSON 字符串
    private static EndpointCallResult postEndpointJson(AppContext context, ApiEndpoint endpoint, String url,
                                                       String plainJson, int requestTimeout) {
        if (isSm4Endpoint(endpoint)) {
            return postSm4Json(context.httpClient, url, plainJson, endpoint.sm4Mode,
                    resolveSm4Key(context, endpoint), requestTimeout)
        }

        HttpResponse<String> response = httpPostJson(context.httpClient, url, plainJson, requestTimeout)
        return new EndpointCallResult(
                statusCode: response.statusCode(),
                rawBody: response.body() ?: '',
                parsedBody: parseResponseObject(response.body())
        )
    }

    // 发送 SM4 请求：Hex 模式 POST 原始 hex，Base64 模式 POST {"param":"..."}
    private static EndpointCallResult postSm4Json(HttpClient httpClient, String url, String plainJson,
                                                  String sm4Mode, String sm4Key, int requestTimeout) {
        String requestCipher
        String requestBody
        HttpResponse<String> response

        if (sm4Mode == Sm4Util.MODE_BASE64) {
            requestCipher = Sm4Util.encryptBase64Ecb(plainJson, sm4Key)
            requestBody = JsonOutput.toJson([param: requestCipher])
            response = httpPostJson(httpClient, url, requestBody, requestTimeout)
        } else {
            requestCipher = Sm4Util.encryptHexEcb(plainJson, sm4Key)
            requestBody = requestCipher
            response = httpPostSm4Hex(httpClient, url, requestBody, requestTimeout)
        }

        String rawBody = response.body() ?: ''
        String responsePlainText = ''
        String decryptError = ''
        try {
            responsePlainText = decryptSm4Response(rawBody, sm4Key, sm4Mode)
        } catch (Exception e) {
            decryptError = e.message ?: e.toString()
        }

        return new EndpointCallResult(
                statusCode: response.statusCode(),
                rawBody: rawBody,
                requestCipher: requestCipher,
                requestBody: requestBody,
                responsePlainText: responsePlainText,
                decryptError: decryptError,
                parsedBody: parseResponseObject(responsePlainText ?: rawBody)
        )
    }

    // 判断接口是否配置了 SM4
    private static boolean isSm4Endpoint(ApiEndpoint endpoint) {
        return endpoint?.sm4Mode in [Sm4Util.MODE_HEX, Sm4Util.MODE_BASE64]
    }

    // 解析 SM4 key，接口配置优先，其次系统设置，最后使用 SM4.groovy 中的 hex key 作为默认值
    private static String resolveSm4Key(AppContext context, ApiEndpoint endpoint) {
        String key = endpoint?.sm4Key ?: context.settings?.sm4Key
        if (!key && endpoint?.sm4Mode == Sm4Util.MODE_HEX) {
            key = DEFAULT_SM4_HEX_KEY
        }
        if (!key) {
            throw new IllegalArgumentException("接口 [${endpoint?.name}] 缺少 SM4 Key")
        }
        return key
    }

    // 批量日志中保留解密失败信息
    private static String sm4DecryptErrorMessage(EndpointCallResult callResult) {
        return callResult?.decryptError ? "SM4 解密失败: ${callResult.decryptError}" : null
    }

    // 解析 HTTP 响应 JSON
    private static Object parseResponseObject(String body) {
        if (!body?.trim()) {
            return null
        }
        try {
            return new JsonSlurper().parseText(body)
        } catch (ignored) {
            return [raw: body]
        }
    }

    // 写入 JSONL 日志：首行 meta，后续每行一条 record
    private static Path writeApiCallLog(ApiEndpoint endpoint, java.util.List<ApiCallLogRecord> records) {
        Path outputPath = resolveBatchOutputPath()
        Files.createDirectories(outputPath.parent)

        java.util.List<String> lines = []
        lines << JsonOutput.toJson([
                type     : 'meta',
                version  : API_CALL_LOG_VERSION,
                apiName  : endpoint.name,
                url      : endpoint.url,
                createdAt: LocalDateTime.now().toString()
        ])
        records.each { ApiCallLogRecord record ->
            lines << JsonOutput.toJson([
                    type      : 'record',
                    row       : record.rowNumber,
                    statusCode: record.statusCode,
                    url       : record.url,
                    request   : record.request,
                    response  : record.response,
                    error     : record.error
            ])
        }

        Files.write(outputPath, lines, StandardCharsets.UTF_8)
        return outputPath
    }

    // 解析 JSONL 日志文件
    private static ApiCallLogFile parseApiCallLog(Path logPath) {
        if (!Files.exists(logPath)) {
            throw new FileNotFoundException("日志不存在: ${logPath.toAbsolutePath()}")
        }

        Map meta = null
        java.util.List<ApiCallLogRecord> records = []

        Files.readAllLines(logPath, StandardCharsets.UTF_8).each { line ->
            String trimmed = line?.trim()
            if (!trimmed || trimmed.startsWith('#')) {
                return
            }

            Object item = new JsonSlurper().parseText(trimmed)
            if (!(item instanceof Map)) {
                return
            }

            if (item.type == 'meta') {
                meta = item
                return
            }

            if (item.type == 'record') {
                records << new ApiCallLogRecord(
                        rowNumber: item.row as Integer,
                        statusCode: item.statusCode as Integer,
                        url: item.url?.toString(),
                        request: item.request instanceof Map ? item.request : null,
                        response: item.response,
                        error: item.error?.toString()
                )
            }
        }

        if (records.isEmpty()) {
            throw new IllegalArgumentException('日志中没有 record 记录')
        }

        return new ApiCallLogFile(meta: meta, records: records)
    }

    // 将日志解析数据展示到数据源表格
    private static void showLogRecordsInSourceTable(JTable sourceTable, java.util.List<ApiCallLogRecord> records) {
        java.util.List<String> columns = buildLogSourceColumns(records)
        DefaultTableModel model = new DefaultTableModel(columns as Object[], 0) {
            @Override
            boolean isCellEditable(int row, int column) {
                return false
            }
        }

        records.each { ApiCallLogRecord record ->
            Map requestMap = record.request instanceof Map ? record.request as Map : [:]
            Map responseMap = record.response instanceof Map ? record.response as Map : [:]
            java.util.List rowValues = columns.collect { column ->
                switch (column) {
                    case '行号': return record.rowNumber
                    case 'HTTP状态': return record.statusCode ?: ''
                    case '错误': return record.error ?: ''
                    default:
                        if (requestMap.containsKey(column)) {
                            return cellText(requestMap[column])
                        }
                        return cellText(responseMap[column])
                }
            }
            model.addRow(new Vector<>(rowValues))
        }

        sourceTable.model = model
        sourceTable.columnModel.columns.each { column ->
            column.preferredWidth = 140
        }
    }

    // 构建日志数据源表格列
    private static java.util.List<String> buildLogSourceColumns(java.util.List<ApiCallLogRecord> records) {
        java.util.Set<String> requestKeys = new LinkedHashSet<>()
        java.util.Set<String> responseKeys = new LinkedHashSet<>()
        records.each { ApiCallLogRecord record ->
            if (record.request instanceof Map) {
                requestKeys.addAll(record.request.keySet()*.toString())
            }
            if (record.response instanceof Map) {
                responseKeys.addAll(record.response.keySet()*.toString())
            }
        }

        java.util.List<String> orderedResponseKeys = []
        RESULT_COLUMN_PRIORITY.each { key ->
            if (responseKeys.remove(key)) {
                orderedResponseKeys << key
            }
        }
        orderedResponseKeys.addAll(responseKeys.sort())

        java.util.List<String> columns = ['行号', 'HTTP状态', '错误']
        columns.addAll(requestKeys.sort())
        orderedResponseKeys.each { key ->
            if (!columns.contains(key)) {
                columns << key
            }
        }
        return columns
    }

    // 更新数据源面板外观（CSV / 日志模式）
    private static void updateSourcePanelAppearance(CsvPanelContext csvContext) {
        if (!csvContext.previewScrollPane) {
            return
        }

        String title = csvContext.sourceMode == 'log' ? '数据源（日志）' : '数据源（CSV，前 3 行）'
        csvContext.previewScrollPane.border = BorderFactory.createTitledBorder(title)
        csvContext.previewScrollPane.verticalScrollBarPolicy = csvContext.sourceMode == 'log'
                ? ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                : ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        csvContext.previewScrollPane.revalidate()
    }

    // 将接口返回数据展示到第二个表格（仅 response）
    private static void showApiResponseInTable(JTable resultTable, java.util.List<ApiCallLogRecord> records) {
        java.util.List<String> columns = buildApiResponseColumns(records)
        DefaultTableModel model = new DefaultTableModel(columns as Object[], 0) {
            @Override
            boolean isCellEditable(int row, int column) {
                return false
            }
        }

        records.each { ApiCallLogRecord record ->
            Map responseMap = record.response instanceof Map ? record.response as Map : [:]
            java.util.List rowValues = columns.collect { column ->
                switch (column) {
                    case '行号': return record.rowNumber
                    case 'HTTP状态': return record.statusCode ?: ''
                    case '错误': return record.error ?: ''
                    default: return cellText(responseMap[column])
                }
            }
            model.addRow(new Vector<>(rowValues))
        }

        resultTable.model = model
        resultTable.columnModel.columns.each { column ->
            column.preferredWidth = 140
        }
    }

    // 构建接口返回表格列（仅 response 字段）
    private static java.util.List<String> buildApiResponseColumns(java.util.List<ApiCallLogRecord> records) {
        return buildResultColumns(records)
    }

    // 将调用结果展示到表格（兼容旧调用）
    private static void showResultsInTable(JTable resultTable, java.util.List<ApiCallLogRecord> records) {
        showApiResponseInTable(resultTable, records)
    }

    // 构建结果表格列
    private static java.util.List<String> buildResultColumns(java.util.List<ApiCallLogRecord> records) {
        java.util.Set<String> responseKeys = new LinkedHashSet<>()
        records.each { ApiCallLogRecord record ->
            if (record.response instanceof Map) {
                responseKeys.addAll(record.response.keySet()*.toString())
            }
        }

        java.util.List<String> orderedKeys = []
        RESULT_COLUMN_PRIORITY.each { key ->
            if (responseKeys.remove(key)) {
                orderedKeys << key
            }
        }
        orderedKeys.addAll(responseKeys.sort())

        java.util.List<String> columns = ['行号', 'HTTP状态', '错误']
        columns.addAll(orderedKeys)
        return columns
    }

    // 清空结果表格
    private static void clearResultTable(JTable resultTable) {
        resultTable.model = new DefaultTableModel() {
            @Override
            boolean isCellEditable(int row, int column) {
                return false
            }
        }
    }

    // 表格单元格文本
    private static String cellText(Object value) {
        if (value == null) {
            return ''
        }
        if (value instanceof Map || value instanceof List) {
            return JsonOutput.toJson(value)
        }
        return value.toString()
    }

    // 校验批量/测试调用前置条件：只要 sourceRows 有数据即可
    private static ApiEndpoint validateSourceCall(AppContext context, CsvPanelContext csvContext) {
        if (!csvContext.sourceRows || csvContext.sourceRows.isEmpty()) {
            JOptionPane.showMessageDialog(context.frame, '数据源为空，请先读取文件', '提示', JOptionPane.WARNING_MESSAGE)
            return null
        }

        ApiEndpoint endpoint = csvContext.apiCombo.selectedItem as ApiEndpoint
        if (!endpoint) {
            JOptionPane.showMessageDialog(context.frame, '请选择接口', '提示', JOptionPane.WARNING_MESSAGE)
            return null
        }

        return endpoint
    }

    // 读取参数映射配置
    private static Map<String, String> readParamMapping(CsvPanelContext csvContext) {
        Map<String, String> mapping = [:]
        csvContext.paramValueFields?.each { param, field ->
            mapping[param] = field.text?.trim() ?: ''
        }
        return mapping
    }

    // 根据 CSV 行和参数值模板构建请求 JSON
    private static Map buildPayloadFromCsvRow(Map<String, String> csvRow, ApiEndpoint endpoint,
                                              Map<String, String> paramValueTemplates) {
        Object template = new JsonSlurper().parseText(endpoint.params)
        if (!(template instanceof Map)) {
            throw new IllegalArgumentException('接口参数模板必须是 JSON 对象')
        }

        Map payload = new LinkedHashMap<>(template)
        paramValueTemplates.each { param, valueTemplate ->
            String trimmed = valueTemplate?.trim()
            if (!trimmed) {
                return
            }
            payload[param] = resolveParamValue(trimmed, csvRow, template[param])
        }
        return payload
    }

    // 解析参数值：{{字段}} 读取 CSV，否则作为固定值
    private static Object resolveParamValue(String valueTemplate, Map<String, String> csvRow, Object templateValue) {
        def matcher = valueTemplate =~ /^\{\{(.+?)\}\}$/
        if (matcher.matches()) {
            String column = matcher[0][1].trim()
            if (!csvRow.containsKey(column)) {
                throw new IllegalArgumentException("CSV列不存在: ${column}")
            }
            return coerceCsvValue(csvRow[column], templateValue)
        }
        return coerceCsvValue(valueTemplate, templateValue)
    }

    // 按模板字段类型转换 CSV 值
    private static Object coerceCsvValue(String rawValue, Object templateValue) {
        String value = rawValue?.trim() ?: ''
        if (templateValue instanceof Number) {
            try {
                return new BigDecimal(value.replace(',', ''))
            } catch (Exception ignored) {
                return value
            }
        }
        if (templateValue instanceof Boolean) {
            return Boolean.parseBoolean(value)
        }
        return value
    }

    // 生成批量结果输出路径
    private static Path resolveBatchOutputPath() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyyMMdd_HHmmss'))
        return Path.of(System.getProperty('user.dir')).resolve(BATCH_OUTPUT_DIR).resolve("batch_${timestamp}.log")
    }

    // 读取完整 CSV 文件
    private static CsvDataset readCsvFile(Path csvPath) {
        if (!Files.exists(csvPath)) {
            throw new FileNotFoundException("文件不存在: ${csvPath.toAbsolutePath()}")
        }

        String csvText = Files.readString(csvPath, StandardCharsets.UTF_8)
        java.util.List<java.util.List<String>> records = parseCsvRecords(csvText)
        if (records.isEmpty()) {
            return new CsvDataset([], [])
        }

        java.util.List<String> headers = new ArrayList<>(records.first())
        if (!headers.isEmpty()) {
            headers[0] = headers[0].replaceFirst('^\\uFEFF', '')
        }

        java.util.List<Map<String, String>> rows = []
        records.drop(1).each { values ->
            if (values.every { it == null || it.trim().isEmpty() }) {
                return
            }
            Map<String, String> row = new LinkedHashMap<>()
            headers.eachWithIndex { header, idx ->
                row[header] = idx < values.size() ? values[idx] : ''
            }
            rows << row
        }

        return new CsvDataset(headers, rows)
    }

    // 截取 CSV 预览数据
    private static CsvPreview toCsvPreview(CsvDataset dataset, int maxDataRows) {
        java.util.List<java.util.List<String>> previewRows = dataset.rows.take(maxDataRows).collect { row ->
            dataset.headers.collect { header -> row[header] ?: '' }
        }
        return new CsvPreview(dataset.headers, previewRows)
    }

    // 读取 CSV 表头及前 N 行数据
    private static CsvPreview readCsvPreview(Path csvPath, int maxDataRows) {
        return toCsvPreview(readCsvFile(csvPath), maxDataRows)
    }

    // 解析 CSV 文本为字段记录列表，支持引号和换行
    private static java.util.List<java.util.List<String>> parseCsvRecords(String text) {
        java.util.List<java.util.List<String>> records = []
        java.util.List<String> record = []
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

    // 用 CSV 预览数据更新表格
    private static void updateCsvTable(JTable csvTable, CsvPreview preview) {
        Object[] columnNames = preview.headers.toArray(new Object[0])
        DefaultTableModel model = new DefaultTableModel(columnNames, 0) {
            @Override
            boolean isCellEditable(int row, int column) {
                return false
            }
        }
        preview.rows.each { row ->
            model.addRow(new Vector<>(row))
        }
        csvTable.model = model
        csvTable.columnModel.columns.each { column ->
            column.preferredWidth = 160
        }
    }

    // 创建系统设置页面
    private static JPanel createSettingsPanel(AppContext context) {
        JTextField apiListFileField = new JTextField(context.settings.apiListFile ?: DEFAULT_API_LIST_FILE, 40)
        JTextField accountIdField = new JTextField(context.settings.accountExternalId ?: '', 40)
        JTextField sm4KeyField = new JTextField(context.settings.sm4Key ?: DEFAULT_SM4_HEX_KEY, 40)
        JTextField sm4HttpUrlField = new JTextField(context.settings.sm4HttpUrl ?: DEFAULT_SM4_HTTP_URL, 40)
        JComboBox<String> sm4ModeCombo = new JComboBox<>(['Hex(SM4Utils)', 'Base64(Hutool)'] as String[])
        String savedMode = context.settings.sm4Mode ?: Sm4Util.MODE_HEX
        sm4ModeCombo.selectedIndex = savedMode == Sm4Util.MODE_BASE64 ? 1 : 0
        JSpinner connectTimeoutSpinner = new JSpinner(new SpinnerNumberModel(context.connectTimeoutSeconds, 1, 300, 1))
        JSpinner requestTimeoutSpinner = new JSpinner(new SpinnerNumberModel(context.requestTimeoutSeconds, 1, 600, 1))

        JPanel formPanel = new JPanel(new GridBagLayout())
        formPanel.border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        GridBagConstraints labelConstraints = new GridBagConstraints(
                gridx: 0, gridy: -1, anchor: GridBagConstraints.EAST,
                insets: new Insets(8, 8, 8, 8)
        )
        GridBagConstraints fieldConstraints = new GridBagConstraints(
                gridx: 1, gridy: -1, fill: GridBagConstraints.HORIZONTAL,
                weightx: 1.0, insets: new Insets(8, 8, 8, 8)
        )

        addSettingRow(formPanel, labelConstraints, fieldConstraints, '接口配置文件', apiListFileField)
        addSettingRow(formPanel, labelConstraints, fieldConstraints, 'Account External ID', accountIdField)
        addSettingRow(formPanel, labelConstraints, fieldConstraints, 'SM4 Key', sm4KeyField)
        addSettingRow(formPanel, labelConstraints, fieldConstraints, 'SM4 URL', sm4HttpUrlField)
        addSettingRow(formPanel, labelConstraints, fieldConstraints, 'SM4 模式', sm4ModeCombo)
        addSettingRow(formPanel, labelConstraints, fieldConstraints, '连接超时 (秒)', connectTimeoutSpinner)
        addSettingRow(formPanel, labelConstraints, fieldConstraints, '请求超时 (秒)', requestTimeoutSpinner)

        JLabel hintLabel = new JLabel("<html>配置文件路径相对于程序运行目录，保存后可在「接口测试」页重新加载接口列表。</html>")
        hintLabel.font = new Font('PingFang SC', Font.PLAIN, 12)
        hintLabel.foreground = new Color(100, 100, 100)
        fieldConstraints.gridy++
        fieldConstraints.gridx = 0
        fieldConstraints.gridwidth = 2
        formPanel.add(hintLabel, fieldConstraints)

        JButton saveButton = new JButton('保存设置')
        saveButton.addActionListener {
            context.settings.apiListFile = apiListFileField.text?.trim() ?: DEFAULT_API_LIST_FILE
            context.settings.accountExternalId = accountIdField.text?.trim() ?: ''
            context.settings.sm4Key = sm4KeyField.text?.trim() ?: DEFAULT_SM4_HEX_KEY
            context.settings.sm4HttpUrl = sm4HttpUrlField.text?.trim() ?: DEFAULT_SM4_HTTP_URL
            context.settings.sm4Mode = sm4ModeCombo.selectedIndex == 1 ? Sm4Util.MODE_BASE64 : Sm4Util.MODE_HEX
            context.settings.connectTimeoutSeconds = connectTimeoutSpinner.value.toString()
            context.settings.requestTimeoutSeconds = requestTimeoutSpinner.value.toString()

            saveSettings(context.settings)
            context.httpClient = newHttpClient(context.connectTimeoutSeconds)
            context.reloadApiList?.run()
            if (context.sm4KeyField) {
                context.sm4KeyField.text = context.settings.sm4Key ?: ''
            }
            if (context.sm4UrlField) {
                context.sm4UrlField.text = context.settings.sm4HttpUrl ?: ''
            }
            if (context.sm4ModeCombo) {
                context.sm4ModeCombo.selectedIndex = context.settings.sm4Mode == Sm4Util.MODE_BASE64 ? 1 : 0
            }

            JOptionPane.showMessageDialog(context.frame, '设置已保存', '提示', JOptionPane.INFORMATION_MESSAGE)
        }

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 12))
        buttonPanel.add(saveButton)

        JPanel panel = new JPanel(new BorderLayout())
        panel.add(formPanel, BorderLayout.NORTH)
        panel.add(buttonPanel, BorderLayout.CENTER)
        return panel
    }

    // 向设置表单添加一行标签和输入控件
    private static void addSettingRow(JPanel panel, GridBagConstraints labelConstraints,
                                      GridBagConstraints fieldConstraints, String label, JComponent field) {
        labelConstraints.gridy++
        fieldConstraints.gridy++
        panel.add(new JLabel(label), labelConstraints)
        panel.add(field, fieldConstraints)
    }

    // 从 settings.txt 读取系统设置
    private static Map<String, String> loadSettings() {
        Map<String, String> settings = [:]
        Path path = resolveSettingsPath()
        if (!Files.exists(path)) {
            settings.apiListFile = DEFAULT_API_LIST_FILE
            return settings
        }

        Files.readAllLines(path, StandardCharsets.UTF_8).each { line ->
            String trimmed = line.trim()
            if (!trimmed || trimmed.startsWith('#') || !trimmed.contains('=')) {
                return
            }
            String[] parts = trimmed.split('=', 2)
            settings[parts[0].trim()] = parts[1].trim()
        }

        if (!settings.apiListFile) {
            settings.apiListFile = DEFAULT_API_LIST_FILE
        }
        return settings
    }

    // 保存系统设置到 settings.txt
    private static void saveSettings(Map<String, String> settings) {
        java.util.List<String> lines = [
                '# 系统设置',
                "apiListFile=${settings.apiListFile ?: DEFAULT_API_LIST_FILE}",
                "accountExternalId=${settings.accountExternalId ?: ''}",
                "connectTimeoutSeconds=${settings.connectTimeoutSeconds ?: '30'}",
                "requestTimeoutSeconds=${settings.requestTimeoutSeconds ?: '60'}",
                "sm4Key=${settings.sm4Key ?: DEFAULT_SM4_HEX_KEY}",
                "sm4HttpUrl=${settings.sm4HttpUrl ?: DEFAULT_SM4_HTTP_URL}",
                "sm4Mode=${settings.sm4Mode ?: Sm4Util.MODE_HEX}"
        ]
        Files.write(resolveSettingsPath(), lines, StandardCharsets.UTF_8)
    }

    // 解析 settings.txt 所在路径
    private static Path resolveSettingsPath() {
        return Path.of(System.getProperty('user.dir')).resolve(SETTINGS_FILE)
    }

    // 从 apis.txt 读取接口列表
    private static java.util.List<ApiEndpoint> loadApiEndpointsFromTxt(Map<String, String> settings) {
        Path path = resolveApiListPath(settings)
        if (!Files.exists(path)) {
            println "接口配置文件不存在: ${path.toAbsolutePath()}"
            return []
        }

        String content = Files.readString(path, StandardCharsets.UTF_8)
        java.util.List<ApiEndpoint> endpoints = parseApiEndpoints(content)
        endpoints.each { endpoint ->
            try {
                new JsonSlurper().parseText(endpoint.params)
            } catch (Exception e) {
                println "接口 [${endpoint.name}] JSON 格式错误: ${e.message}"
            }
        }
        return endpoints
    }

    // 解析 apis.txt 所在路径
    private static Path resolveApiListPath(Map<String, String> settings) {
        String fileName = settings?.apiListFile ?: DEFAULT_API_LIST_FILE
        return Path.of(System.getProperty('user.dir')).resolve(fileName)
    }

    // 解析 txt 内容为接口列表，格式: [名称] + URL + 可选 @配置 + JSON
    private static java.util.List<ApiEndpoint> parseApiEndpoints(String content) {
        java.util.List<ApiEndpoint> endpoints = []
        String[] blocks = content.split(/(?m)^(?=\[)/)

        blocks.each { block ->
            String text = block?.trim()
            if (!text) {
                return
            }

            def matcher = text =~ /^\[(.+?)\]\s*\r?\n([\s\S]*)$/
            if (!matcher.matches()) {
                return
            }

            String name = matcher[0][1].trim()
            java.util.List<String> lines = matcher[0][2].readLines()
            int urlIndex = lines.findIndexOf { String line ->
                String trimmed = line.trim()
                trimmed && !trimmed.startsWith('#')
            }
            if (urlIndex < 0) {
                return
            }

            String url = lines[urlIndex].trim()
            Map<String, String> options = [:]
            int paramsStart = urlIndex + 1
            while (paramsStart < lines.size()) {
                String trimmed = lines[paramsStart].trim()
                if (!trimmed || trimmed.startsWith('#')) {
                    paramsStart++
                    continue
                }
                if (!trimmed.startsWith('@')) {
                    break
                }
                parseEndpointOption(trimmed, options)
                paramsStart++
            }

            String params = paramsStart < lines.size() ? lines.drop(paramsStart).join('\n').trim() : ''
            if (!name || !url || !params) {
                return
            }

            endpoints << new ApiEndpoint(name, url, params,
                    normalizeSm4Mode(options.sm4Mode), options.sm4Key)
        }

        return endpoints
    }

    // 解析接口块中的 @key=value 配置行
    private static void parseEndpointOption(String line, Map<String, String> options) {
        String text = line.substring(1).trim()
        int equalsIndex = text.indexOf('=')
        if (equalsIndex <= 0) {
            return
        }

        String key = text.substring(0, equalsIndex).trim()
        String value = text.substring(equalsIndex + 1).trim()
        if (key) {
            options[key] = value
        }
    }

    // 标准化 SM4 模式配置
    private static String normalizeSm4Mode(String mode) {
        String value = mode?.trim()?.toLowerCase()
        if (!value) {
            return ''
        }
        if (value in ['hex', 'sm4hex', 'sm4_hex']) {
            return Sm4Util.MODE_HEX
        }
        if (value in ['base64', 'b64', 'hutool']) {
            return Sm4Util.MODE_BASE64
        }
        return ''
    }

    // 创建左侧接口列表面板，点击接口名称自动填充 URL 和 JSON 参数
    private static JPanel createApiListPanel(AppContext context) {
        DefaultListModel<ApiEndpoint> listModel = new DefaultListModel<>()
        context.apiListModel = listModel

        JList<ApiEndpoint> apiList = new JList<>(listModel)
        context.apiList = apiList
        apiList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        apiList.font = new Font('PingFang SC', Font.PLAIN, 13)
        apiList.fixedCellHeight = 32

        apiList.addListSelectionListener { event ->
            if (event.valueIsAdjusting) {
                return
            }
            ApiEndpoint selected = apiList.selectedValue
            if (selected) {
                applyApiEndpoint(selected, context.urlField, context.jsonField)
            }
        }

        JPanel panel = new JPanel(new BorderLayout())
        panel.preferredSize = new Dimension(220, 0)

        JScrollPane listScrollPane = new JScrollPane(apiList)
        listScrollPane.border = BorderFactory.createTitledBorder('接口列表')
        panel.add(listScrollPane, BorderLayout.CENTER)

        return panel
    }

    // 将选中接口的 URL 和参数填充到输入区域
    private static void applyApiEndpoint(ApiEndpoint endpoint, JTextField urlField, JTextArea jsonField) {
        urlField.text = endpoint.url
        try {
            jsonField.text = prettyJson(new JsonSlurper().parseText(endpoint.params))
        } catch (Exception ignored) {
            jsonField.text = endpoint.params
        }
        jsonField.caretPosition = 0
    }

    // 当前 URL 仍匹配左侧选中接口时，使用该接口的附加配置（例如 SM4）
    private static ApiEndpoint resolveSelectedEndpoint(AppContext context, String url) {
        ApiEndpoint selected = context.apiList?.selectedValue
        return selected && selected.url == url ? selected : null
    }

    // 绑定请求按钮点击事件，校验参数并在后台发起 POST 请求
    private static void bindRequestButton(AppContext context, JButton requestButton) {
        requestButton.addActionListener {
            String url = context.urlField.text?.trim()
            if (!url) {
                JOptionPane.showMessageDialog(context.frame, '请输入 URL', '提示', JOptionPane.WARNING_MESSAGE)
                return
            }

            String jsonBody = context.jsonField.text?.trim()
            if (!jsonBody) {
                JOptionPane.showMessageDialog(context.frame, '请输入 JSON 参数', '提示', JOptionPane.WARNING_MESSAGE)
                return
            }

            try {
                new JsonSlurper().parseText(jsonBody)
            } catch (Exception e) {
                JOptionPane.showMessageDialog(context.frame, "JSON 格式错误: ${e.message}", '提示', JOptionPane.WARNING_MESSAGE)
                return
            }

            requestButton.enabled = false
            context.resultArea.text = "请求中: ${url}\n"

            int requestTimeout = context.requestTimeoutSeconds
            Thread.start {
                try {
                    ApiEndpoint endpoint = resolveSelectedEndpoint(context, url)
                    EndpointCallResult callResult = postEndpointJson(context, endpoint, url, jsonBody, requestTimeout)
                    String displayText = isSm4Endpoint(endpoint)
                            ? formatSm4Result(callResult.statusCode, url, endpoint.sm4Mode,
                            callResult.requestCipher, callResult.requestBody, callResult.rawBody,
                            callResult.responsePlainText, callResult.decryptError)
                            : formatResult(callResult.statusCode, url, callResult.rawBody)
                    SwingUtilities.invokeLater {
                        showResult(context.resultArea, displayText)
                    }
                } catch (Exception e) {
                    String displayText = formatError(url, e)
                    SwingUtilities.invokeLater {
                        showResult(context.resultArea, displayText)
                    }
                } finally {
                    SwingUtilities.invokeLater {
                        requestButton.enabled = true
                    }
                }
            }
        }
    }

    // 创建 HTTP 客户端实例
    private static HttpClient newHttpClient(int connectTimeoutSeconds) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
    }

    // 发送 POST 请求，请求体为 JSON 字符串
    private static HttpResponse<String> httpPostJson(HttpClient httpClient, String url, String jsonBody, int timeoutSeconds) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header('Content-Type', 'application/json; charset=UTF-8')
                .header('Accept', 'application/json')
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build()

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }

    // 发送 POST 请求，Hex 密文作为 body（Content-Type: application/json，与参考 http 一致）
    private static HttpResponse<String> httpPostSm4Hex(HttpClient httpClient, String url, String hexBody, int timeoutSeconds) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header('Charset', 'utf-8')
                .header('Content-Type', 'application/json')
                .POST(HttpRequest.BodyPublishers.ofString(hexBody, StandardCharsets.UTF_8))
                .build()

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }

    // 将响应文本写入结果区域并滚动到顶部
    private static void showResult(JTextArea resultArea, String text) {
        resultArea.text = text
        resultArea.caretPosition = 0
    }

    // 格式化 HTTP 响应结果为可读文本
    private static String formatResult(int statusCode, String url, String body) {
        return "状态码: ${statusCode}\nURL: ${url}\n\n${formatBody(body)}"
    }

    // 格式化请求异常信息
    private static String formatError(String url, Exception e) {
        return decodeUnicodeEscapes("请求失败: ${url}\n\n${e.class.simpleName}: ${e.message}")
    }

    // 格式化响应体，JSON 则美化输出并解码 Unicode，否则解码后原样返回
    private static String formatBody(String body) {
        if (!body?.trim()) {
            return ''
        }
        try {
            Object parsed = new JsonSlurper().parseText(body)
            return prettyJson(parsed)
        } catch (ignored) {
            return decodeUnicodeEscapes(body)
        }
    }

    // 将对象格式化为可读 JSON 字符串，并解码 Unicode 转义
    private static String prettyJson(Object value) {
        return decodeUnicodeEscapes(JsonOutput.prettyPrint(JsonOutput.toJson(value)))
    }

    // 将 \uXXXX 形式的 Unicode 转义解码为可读字符
    private static String decodeUnicodeEscapes(String text) {
        if (!text) {
            return text
        }
        return text.replaceAll(/\\u([0-9a-fA-F]{4})/) { _, hex ->
            String.valueOf((char) Integer.parseInt(hex, 16))
        }
    }

    // 应用上下文，在 Tab 页面之间共享状态
    private static class AppContext {
        JFrame frame
        Map<String, String> settings
        HttpClient httpClient
        JTextField urlField
        JTextArea jsonField
        JTextArea resultArea
        JTextField sm4KeyField
        JComboBox<String> sm4ModeCombo
        JTextField sm4UrlField
        JTextArea sm4JsonField
        JTextArea sm4ResultArea
        JPanel apiListPanel
        DefaultListModel<ApiEndpoint> apiListModel
        JList<ApiEndpoint> apiList
        Runnable reloadApiList
        Runnable reloadCsvApiCombo
        CsvPanelContext csvPanelContext
        int endpointCount = 0

        int getConnectTimeoutSeconds() {
            return parseIntSetting(settings?.connectTimeoutSeconds, 30)
        }

        int getRequestTimeoutSeconds() {
            return parseIntSetting(settings?.requestTimeoutSeconds, 60)
        }

        private static int parseIntSetting(String value, int defaultValue) {
            try {
                return Integer.parseInt(value)
            } catch (Exception ignored) {
                return defaultValue
            }
        }
    }

    // 接口定义：名称、URL、JSON 参数字符串
    private static class ApiEndpoint {
        final String name
        final String url
        final String params
        final String sm4Mode
        final String sm4Key

        ApiEndpoint(String name, String url, String params, String sm4Mode = '', String sm4Key = '') {
            this.name = name
            this.url = url
            this.params = params
            this.sm4Mode = sm4Mode ?: ''
            this.sm4Key = sm4Key ?: ''
        }

        @Override
        String toString() {
            return name
        }
    }

    // 单次 API 调用记录
    private static class ApiCallLogRecord {
        int rowNumber
        int statusCode
        String url
        Map request
        Object response
        String error
    }

    // HTTP 调用结果，SM4 接口会额外保留密文和解密文本
    private static class EndpointCallResult {
        int statusCode
        String rawBody = ''
        Object parsedBody
        String requestCipher = ''
        String requestBody = ''
        String responsePlainText = ''
        String decryptError = ''
    }

    // 解析后的日志文件
    private static class ApiCallLogFile {
        Map meta
        java.util.List<ApiCallLogRecord> records
    }

    // CSV 页面上下文
    private static class CsvPanelContext {
        JTextField filePathField
        JTable previewTable
        JScrollPane previewScrollPane
        String sourceMode
        JComboBox<ApiEndpoint> apiCombo
        JPanel mappingPanel
        Map<String, JTextField> paramValueFields
        JTable resultTable
        JLabel infoLabel
        JButton batchButton
        JButton stopButton
        volatile boolean batchStopRequested = false
        java.util.List<String> sourceHeaders = []
        java.util.List sourceRows = []          // 读取文件后缓存在内存，批量操作循环此数组
        java.util.List<ApiCallLogRecord> resultRecords = []
    }

    // CSV 完整数据
    private static class CsvDataset {
        final java.util.List<String> headers
        final java.util.List<Map<String, String>> rows

        CsvDataset(java.util.List<String> headers, java.util.List<Map<String, String>> rows) {
            this.headers = headers
            this.rows = rows
        }
    }

    // CSV 预览数据：表头 + 数据行
    private static class CsvPreview {
        final java.util.List<String> headers
        final java.util.List<java.util.List<String>> rows

        CsvPreview(java.util.List<String> headers, java.util.List<java.util.List<String>> rows) {
            this.headers = headers
            this.rows = rows
        }
    }
}
