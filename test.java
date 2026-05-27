@ExtendWith(MockitoExtension.class)
class TarDomainAcquisitionUseCaseTest {

    @Mock StorageService storageService;
    @Mock ParsingService parsingService;
    @Mock ArchivingService archivingService;

    TarDomainAcquisitionUseCase useCase;

    static final String ONGOING  = "ongoing";
    static final String IN_DIR   = "indir";
    static final String SENT     = "sent";
    static final String REJECTED = "rejected";
    static final String REGEX    = ".*\\.xml";
    static final int    MAX      = 10;

    @BeforeEach
    void setUp() {
        useCase = new TarDomainAcquisitionUseCase(
            storageService, parsingService, archivingService,
            REGEX, ONGOING, IN_DIR, SENT, MAX, REJECTED
        );
    }

    // ─── receivedFiles ───────────────────────────────────────────────────────

    @Test
    void receivedFiles_emptyList_noLoadNorWrite() {
        when(storageService.listFilePaths(IN_DIR, MAX))
            .thenReturn(List.of());

        useCase.receivedFiles();

        verify(storageService, never()).load(anyString());
        verify(storageService, never()).write(any());
    }

    @Test
    void receivedFiles_validTarGz_extractsXmlAndPdf() {
        when(storageService.listFilePaths(IN_DIR, MAX))
            .thenReturn(List.of(IN_DIR + "/test.tar.gz"));
        when(storageService.load(IN_DIR + "/test.tar.gz"))
            .thenReturn(loadTestTar());

        useCase.receivedFiles();

        ArgumentCaptor<DataFile> captor = ArgumentCaptor.forClass(DataFile.class);
        verify(storageService, times(2)).write(captor.capture());

        List<String> paths = captor.getAllValues()
            .stream().map(DataFile::getPath).toList();
        assertThat(paths).anyMatch(p -> p.endsWith(".xml"));
        assertThat(paths).anyMatch(p -> p.endsWith(".pdf"));
        assertThat(paths).allMatch(p -> p.startsWith(ONGOING));
    }

    @Test
    void receivedFiles_validTarGz_sameBaseNameForXmlAndPdf() {
        when(storageService.listFilePaths(IN_DIR, MAX))
            .thenReturn(List.of(IN_DIR + "/test.tar.gz"));
        when(storageService.load(IN_DIR + "/test.tar.gz"))
            .thenReturn(loadTestTar());

        useCase.receivedFiles();

        ArgumentCaptor<DataFile> captor = ArgumentCaptor.forClass(DataFile.class);
        verify(storageService, times(2)).write(captor.capture());

        List<String> paths = captor.getAllValues()
            .stream().map(DataFile::getPath).toList();
        String xmlBase = paths.stream()
            .filter(p -> p.endsWith(".xml")).findFirst().orElseThrow()
            .replace(".xml", "");
        String pdfBase = paths.stream()
            .filter(p -> p.endsWith(".pdf")).findFirst().orElseThrow()
            .replace(".pdf", "");
        assertThat(xmlBase).isEqualTo(pdfBase);
    }

    @Test
    void receivedFiles_multipleTars_allExtracted() {
        when(storageService.listFilePaths(IN_DIR, MAX))
            .thenReturn(List.of(IN_DIR + "/a.tar.gz", IN_DIR + "/b.tar.gz"));
        when(storageService.load(anyString()))
            .thenReturn(loadTestTar())
            .thenReturn(loadTestTar());

        useCase.receivedFiles();

        verify(storageService, times(4)).write(any());
    }

    @Test
    void receivedFiles_corruptTar_doesNotThrow() {
        when(storageService.listFilePaths(IN_DIR, MAX))
            .thenReturn(List.of(IN_DIR + "/bad.tar.gz"));
        when(storageService.load(anyString()))
            .thenReturn(new ByteArrayInputStream(new byte[]{0x00, 0x01}));

        assertDoesNotThrow(() -> useCase.receivedFiles());
        verify(storageService, never()).write(any());
    }

    @Test
    void receivedFiles_loadThrows_doesNotPropagateException() {
        when(storageService.listFilePaths(IN_DIR, MAX))
            .thenReturn(List.of(IN_DIR + "/test.tar.gz"));
        when(storageService.load(anyString()))
            .thenThrow(new RuntimeException("COS unavailable"));

        assertDoesNotThrow(() -> useCase.receivedFiles());
    }

    // ─── sendFiles ───────────────────────────────────────────────────────────

    @Test
    void sendFiles_emptyOngoing_noProcessing() {
        when(storageService.list(ONGOING, MAX)).thenReturn(List.of());

        useCase.sendFiles();

        verify(parsingService, never()).parse(any());
        verify(archivingService, never()).send(any());
        verify(storageService, never()).move(any(DataFile.class), any(DataFile.class));
    }

    @Test
    void sendFiles_onlyPdfFiles_noneProcessed() {
        when(storageService.list(ONGOING, MAX))
            .thenReturn(List.of(new DataFile(null, ONGOING + "/doc.pdf")));

        useCase.sendFiles();

        verify(parsingService, never()).parse(any());
        verify(storageService, never()).move(any(DataFile.class), any(DataFile.class));
    }

    @Test
    void sendFiles_parseException_movedToRejected() throws Exception {
        when(storageService.list(ONGOING, MAX))
            .thenReturn(List.of(new DataFile(null, ONGOING + "/doc.xml")));
        when(storageService.load(ONGOING + "/doc.xml"))
            .thenReturn(new ByteArrayInputStream("<xml/>".getBytes()));
        when(parsingService.parse(any()))
            .thenThrow(new FileParsingException("parse error"));
        when(storageService.read(ONGOING + "/doc.pdf"))
            .thenReturn(new DataFile(null, ONGOING + "/doc.pdf"));

        useCase.sendFiles();

        verify(archivingService, never()).send(any());
        ArgumentCaptor<DataFile> captor = ArgumentCaptor.forClass(DataFile.class);
        verify(storageService, times(2))
            .move(any(DataFile.class), captor.capture());
        assertThat(captor.getAllValues())
            .allMatch(df -> df.getPath().startsWith(REJECTED));
    }

    @Test
    void sendFiles_archivingException_movedToRejected() throws Exception {
        setupValidXmlFile("doc");
        when(archivingService.send(any()))
            .thenReturn(Mono.error(
                new ArchivingNewDocException("error", HttpStatus.INTERNAL_SERVER_ERROR)
            ));
        when(storageService.read(ONGOING + "/doc.pdf"))
            .thenReturn(new DataFile(null, ONGOING + "/doc.pdf"));

        useCase.sendFiles();

        ArgumentCaptor<DataFile> captor = ArgumentCaptor.forClass(DataFile.class);
        verify(storageService, times(2))
            .move(any(DataFile.class), captor.capture());
        assertThat(captor.getAllValues())
            .allMatch(df -> df.getPath().startsWith(REJECTED));
    }

    @Test
    void sendFiles_parseException_tagStatusSetToError() throws Exception {
        when(storageService.list(ONGOING, MAX))
            .thenReturn(List.of(new DataFile(null, ONGOING + "/doc.xml")));
        when(storageService.load(ONGOING + "/doc.xml"))
            .thenReturn(new ByteArrayInputStream("<xml/>".getBytes()));
        when(parsingService.parse(any()))
            .thenThrow(new FileParsingException("parse error"));
        when(storageService.read(ONGOING + "/doc.pdf"))
            .thenReturn(new DataFile(null, ONGOING + "/doc.pdf"));

        useCase.sendFiles();

        ArgumentCaptor<DataFile> captor = ArgumentCaptor.forClass(DataFile.class);
        verify(storageService, times(2))
            .move(captor.capture(), any(DataFile.class));
        DataFile xmlSrc = captor.getAllValues().stream()
            .filter(df -> df.getPath().endsWith(".xml"))
            .findFirst().orElseThrow();
        assertThat(xmlSrc.getTags().get(Constants.STATUS))
            .isEqualTo(Constants.STATUS_ERROR);
    }

    @Test
    void sendFiles_moveToRejectedThrows_doesNotPropagate() throws Exception {
        when(storageService.list(ONGOING, MAX))
            .thenReturn(List.of(new DataFile(null, ONGOING + "/doc.xml")));
        when(storageService.load(ONGOING + "/doc.xml"))
            .thenReturn(new ByteArrayInputStream("<xml/>".getBytes()));
        when(parsingService.parse(any()))
            .thenThrow(new FileParsingException("parse error"));
        when(storageService.read(ONGOING + "/doc.pdf"))
            .thenReturn(new DataFile(null, ONGOING + "/doc.pdf"));
        doThrow(new DocStorageException("move failed"))
            .when(storageService).move(any(DataFile.class), any(DataFile.class));

        assertDoesNotThrow(() -> useCase.sendFiles());
    }

    @Test
    void sendFiles_mixedXmlAndPdf_onlyXmlProcessed() throws Exception {
        when(storageService.list(ONGOING, MAX)).thenReturn(List.of(
            new DataFile(null, ONGOING + "/doc.xml"),
            new DataFile(null, ONGOING + "/doc.pdf"),
            new DataFile(null, ONGOING + "/other.pdf")
        ));
        when(storageService.load(ONGOING + "/doc.xml"))
            .thenReturn(new ByteArrayInputStream("<xml/>".getBytes()));
        when(parsingService.parse(any()))
            .thenThrow(new FileParsingException("fail"));
        when(storageService.read(ONGOING + "/doc.pdf"))
            .thenReturn(new DataFile(null, ONGOING + "/doc.pdf"));

        useCase.sendFiles();

        // seul le XML a été traité
        verify(parsingService, times(1)).parse(any());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void setupValidXmlFile(String baseName) throws Exception {
        when(storageService.list(ONGOING, MAX))
            .thenReturn(List.of(new DataFile(null, ONGOING + "/" + baseName + ".xml")));
        when(storageService.load(ONGOING + "/" + baseName + ".xml"))
            .thenReturn(new ByteArrayInputStream("<xml/>".getBytes()));
        when(parsingService.parse(any()))
            .thenReturn(buildParsedFile());
        when(storageService.load(ONGOING + "/" + baseName + ".pdf"))
            .thenReturn(new ByteArrayInputStream(fakePdfBytes()));
    }

    private InputStream loadTestTar() {
        return getClass().getClassLoader()
            .getResourceAsStream("data/test.tar.gz");
    }

    private byte[] fakePdfBytes() {
        return "%PDF-1.4\nfake\n%%EOF".getBytes(StandardCharsets.UTF_8);
    }

    private ParsedFile buildParsedFile() {
        ArcFileMetadata meta = new ArcFileMetadata();
        meta.setEaasFileName("doc.xml");
        meta.setFileType("PDF");
        meta.setContractId("CONTRAT_01");
        meta.setCustomerId("CLIENT_01");
        ParsedFile pf = new ParsedFile();
        pf.setHeader(meta);
        return pf;
    }
}
