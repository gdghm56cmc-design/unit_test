// ─── receivedFiles : chemin heureux (couvre extractAndMove lignes 74-83) ─────

@Test
void receivedFiles_validTarGz_writesXmlAndPdfToOngoing() {
    when(storageService.listFilePaths(IN_DIR, MAX))
        .thenReturn(List.of(IN_DIR + "/testtar.tar.gz"));
    when(storageService.load(IN_DIR + "/testtar.tar.gz"))
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
void receivedFiles_validTarGz_sameBaseNameXmlAndPdf() {
    when(storageService.listFilePaths(IN_DIR, MAX))
        .thenReturn(List.of(IN_DIR + "/testtar.tar.gz"));
    when(storageService.load(IN_DIR + "/testtar.tar.gz"))
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
        .thenReturn(List.of(
            IN_DIR + "/testtar.tar.gz",
            IN_DIR + "/testtar.tar.gz"
        ));
    when(storageService.load(anyString()))
        .thenReturn(loadTestTar())
        .thenReturn(loadTestTar());

    useCase.receivedFiles();

    verify(storageService, times(4)).write(any());
}

// ─── sendFiles : couvre moveInError (lignes 92-100) ──────────────────────────

@Test
void sendFiles_parseException_xmlAndPdfMovedToRejected() throws Exception {
    when(storageService.list(ONGOING, MAX))
        .thenReturn(List.of(new DataFile(null, ONGOING + "/doc.xml")));
    when(storageService.load(ONGOING + "/doc.xml"))
        .thenReturn(new ByteArrayInputStream("<xml/>".getBytes()));
    when(parsingService.parse(any()))
        .thenThrow(new FileParsingException("parse error"));
    when(storageService.read(ONGOING + "/doc.pdf"))
        .thenReturn(new DataFile(null, ONGOING + "/doc.pdf"));

    useCase.sendFiles();

    ArgumentCaptor<DataFile> dstCaptor = ArgumentCaptor.forClass(DataFile.class);
    verify(storageService, times(2)).move(any(), dstCaptor.capture());
    assertThat(dstCaptor.getAllValues())
        .allMatch(df -> df.getPath().startsWith(REJECTED));
    assertThat(dstCaptor.getAllValues())
        .anyMatch(df -> df.getPath().endsWith(".xml"))
        .anyMatch(df -> df.getPath().endsWith(".pdf"));
}

@Test
void sendFiles_parseException_statusTagSetToError() throws Exception {
    when(storageService.list(ONGOING, MAX))
        .thenReturn(List.of(new DataFile(null, ONGOING + "/doc.xml")));
    when(storageService.load(ONGOING + "/doc.xml"))
        .thenReturn(new ByteArrayInputStream("<xml/>".getBytes()));
    when(parsingService.parse(any()))
        .thenThrow(new FileParsingException("parse error"));
    when(storageService.read(ONGOING + "/doc.pdf"))
        .thenReturn(new DataFile(null, ONGOING + "/doc.pdf"));

    useCase.sendFiles();

    ArgumentCaptor<DataFile> srcCaptor = ArgumentCaptor.forClass(DataFile.class);
    verify(storageService, times(2)).move(srcCaptor.capture(), any());
    DataFile xmlMoved = srcCaptor.getAllValues().stream()
        .filter(df -> df.getPath().endsWith(".xml"))
        .findFirst().orElseThrow();
    assertThat(xmlMoved.getTags().get(Constants.STATUS))
        .isEqualTo(Constants.STATUS_ERROR);
}

@Test
void sendFiles_moveInErrorDocStorageException_doesNotPropagate() throws Exception {
    when(storageService.list(ONGOING, MAX))
        .thenReturn(List.of(new DataFile(null, ONGOING + "/doc.xml")));
    when(storageService.load(ONGOING + "/doc.xml"))
        .thenReturn(new ByteArrayInputStream("<xml/>".getBytes()));
    when(parsingService.parse(any()))
        .thenThrow(new FileParsingException("parse error"));
    when(storageService.read(ONGOING + "/doc.pdf"))
        .thenReturn(new DataFile(null, ONGOING + "/doc.pdf"));
    doThrow(new DocStorageException("storage error"))
        .when(storageService).move(any(), any());

    assertDoesNotThrow(() -> useCase.sendFiles());
}

@Test
void sendFiles_archivingException_movedToRejected() throws Exception {
    when(storageService.list(ONGOING, MAX))
        .thenReturn(List.of(new DataFile(null, ONGOING + "/doc.xml")));
    when(storageService.load(ONGOING + "/doc.xml"))
        .thenReturn(new ByteArrayInputStream("<xml/>".getBytes()));
    when(parsingService.parse(any())).thenReturn(buildParsedFile());
    when(storageService.load(ONGOING + "/doc.pdf"))
        .thenReturn(new ByteArrayInputStream(fakePdfBytes()));
    when(archivingService.send(any()))
        .thenReturn(Mono.error(
            new ArchivingNewDocException("error", HttpStatus.INTERNAL_SERVER_ERROR)
        ));
    when(storageService.read(ONGOING + "/doc.pdf"))
        .thenReturn(new DataFile(null, ONGOING + "/doc.pdf"));

    useCase.sendFiles();

    ArgumentCaptor<DataFile> dstCaptor = ArgumentCaptor.forClass(DataFile.class);
    verify(storageService, times(2)).move(any(), dstCaptor.capture());
    assertThat(dstCaptor.getAllValues())
        .allMatch(df -> df.getPath().startsWith(REJECTED));
}

@Test
void sendFiles_multipleFiles_eachProcessedIndependently() throws Exception {
    when(storageService.list(ONGOING, MAX)).thenReturn(List.of(
        new DataFile(null, ONGOING + "/a.xml"),
        new DataFile(null, ONGOING + "/b.xml")
    ));
    when(storageService.load(anyString()))
        .thenReturn(new ByteArrayInputStream("<xml/>".getBytes()));
    when(parsingService.parse(any()))
        .thenThrow(new FileParsingException("fail"));
    when(storageService.read(ONGOING + "/a.pdf"))
        .thenReturn(new DataFile(null, ONGOING + "/a.pdf"));
    when(storageService.read(ONGOING + "/b.pdf"))
        .thenReturn(new DataFile(null, ONGOING + "/b.pdf"));

    useCase.sendFiles();

    verify(parsingService, times(2)).parse(any());
    verify(storageService, times(4)).move(any(), any()); // 2 xml + 2 pdf
}
