package com.bnpparibas.itg.ps.ap28004.archiving.appli.usecase;

import com.bnpparibas.itg.ps.ap28004.archiving.domain.exception.ArchivingNewDocException;
import com.bnpparibas.itg.ps.ap28004.archiving.domain.exception.DocStorageException;
import com.bnpparibas.itg.ps.ap28004.archiving.domain.exception.FileParsingException;
import com.bnpparibas.itg.ps.ap28004.archiving.domain.model.ArcFileMetadata;
import com.bnpparibas.itg.ps.ap28004.archiving.domain.model.DataFile;
import com.bnpparibas.itg.ps.ap28004.archiving.domain.model.ParsedFile;
import com.bnpparibas.itg.ps.ap28004.archiving.domain.port.outbound.ArchivingService;
import com.bnpparibas.itg.ps.ap28004.archiving.domain.port.outbound.ParsingService;
import com.bnpparibas.itg.ps.ap28004.archiving.domain.port.outbound.StorageService;
import com.bnpparibas.itg.ps.ap28004.archiving.infra.ArchiverInternalInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

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
        when(storageService.listFilePaths(IN_DIR, MAX)).thenReturn(List.of());

        useCase.receivedFiles();

        verify(storageService, never()).load(anyString());
        verify(storageService, never()).write(any());
    }

    @Test
    void receivedFiles_validTarGz_extractsXmlAndPdf() throws Exception {
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
    void receivedFiles_validTarGz_xmlAndPdfHaveSameBaseName() throws Exception {
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
            .filter(p -> p.endsWith(".xml"))
            .findFirst().orElseThrow()
            .replace(".xml", "");
        String pdfBase = paths.stream()
            .filter(p -> p.endsWith(".pdf"))
            .findFirst().orElseThrow()
            .replace(".pdf", "");
        assertThat(xmlBase).isEqualTo(pdfBase);
    }

    @Test
    void receivedFiles_multipleTars_allExtracted() throws Exception {
        when(storageService.listFilePaths(IN_DIR, MAX))
            .thenReturn(List.of(
                IN_DIR + "/test.tar.gz",
                IN_DIR + "/test.tar.gz"
            ));
        when(storageService.load(anyString()))
            .thenReturn(loadTestTar())
            .thenReturn(loadTestTar());

        useCase.receivedFiles();

        verify(storageService, times(4)).write(any()); // 2 xml + 2 pdf
    }

    @Test
    void receivedFiles_corruptTar_doesNotThrow() {
        when(storageService.listFilePaths(IN_DIR, MAX))
            .thenReturn(List.of(IN_DIR + "/bad.tar.gz"));
        when(storageService.load(IN_DIR + "/bad.tar.gz"))
            .thenReturn(new ByteArrayInputStream(new byte[]{0x00, 0x01, 0x02}));

        assertDoesNotThrow(() -> useCase.receivedFiles());
        verify(storageService, never()).write(any());
    }

    @Test
    void receivedFiles_loadThrowsException_doesNotThrow() {
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
        verify(storageService, never()).move(any(), any());
    }

    @Test
    void sendFiles_onlyPdfFiles_noneProcessed() {
        when(storageService.list(ONGOING, MAX))
            .thenReturn(List.of(new DataFile(null, ONGOING + "/doc.pdf")));

        useCase.sendFiles();

        verify(parsingService, never()).parse(any());
        verify(storageService, never()).move(any(), any());
    }

    @Test
    void sendFiles_validFile_sentAndMovedToSentDir() throws Exception {
        setupValidXmlFile("doc");
        ArchiverInternalInfo info = new ArchiverInternalInfo("ctx1", "docId1");
        when(archivingService.send(any())).thenReturn(Mono.just(info));
        when(storageService.read(ONGOING + "/doc.pdf"))
            .thenReturn(new DataFile(null, ONGOING + "/doc.pdf"));

        useCase.sendFiles();

        verify(archivingService).send(argThat(df ->
            df.getMetadata() != null && df.getAttachedDatas() != null
        ));
        ArgumentCaptor<DataFile> captor = ArgumentCaptor.forClass(DataFile.class);
        verify(storageService, times(2)).move(any(), captor.capture());
        assertThat(captor.getAllValues())
            .allMatch(df -> df.getPath().startsWith(SENT));
    }

    @Test
    void sendFiles_validFile_tagsSetAfterSend() throws Exception {
        setupValidXmlFile("doc");
        when(archivingService.send(any()))
            .thenReturn(Mono.just(new ArchiverInternalInfo("ctx42", "doc42")));
        when(storageService.read(ONGOING + "/doc.pdf"))
            .thenReturn(new DataFile(null, ONGOING + "/doc.pdf"));

        useCase.sendFiles();

        ArgumentCaptor<DataFile> captor = ArgumentCaptor.forClass(DataFile.class);
        verify(storageService, times(2)).move(captor.capture(), any());
        DataFile sent = captor.getAllValues().stream()
            .filter(df -> df.getPath().endsWith(".xml"))
            .findFirst().orElseThrow();
        assertThat(sent.getTags().get(Constants.STATUS)).isEqualTo(Constants.STATUS_SENT);
        assertThat(sent.getTags().get("context_id")).isEqualTo("ctx42");
        assertThat(sent.getTags().get("document_id")).isEqualTo("doc42");
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
        verify(storageService, times(2)).move(any(), captor.capture());
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
        verify(storageService, times(2)).move(any(), captor.capture());
        assertThat(captor.getAllValues())
            .allMatch(df -> df.getPath().startsWith(REJECTED));
    }

    @Test
    void sendFiles_moveInErrorThrows_doesNotPropagateException() throws Exception {
        when(storageService.list(ONGOING, MAX))
            .thenReturn(List.of(new DataFile(null, ONGOING + "/doc.xml")));
        when(storageService.load(ONGOING + "/doc.xml"))
            .thenReturn(new ByteArrayInputStream("<xml/>".getBytes()));
        when(parsingService.parse(any()))
            .thenThrow(new FileParsingException("parse error"));
        when(storageService.read(ONGOING + "/doc.pdf"))
            .thenReturn(new DataFile(null, ONGOING + "/doc.pdf"));
        doThrow(new DocStorageException("move failed"))
            .when(storageService).move(any(), any());

        assertDoesNotThrow(() -> useCase.sendFiles());
    }

    @Test
    void sendFiles_mixedFiles_correctRouting() throws Exception {
        DataFile valid   = new DataFile(null, ONGOING + "/good.xml");
        DataFile invalid = new DataFile(null, ONGOING + "/bad.xml");

        when(storageService.list(ONGOING, MAX))
            .thenReturn(List.of(valid, invalid));

        // good.xml → parse OK
        when(storageService.load(ONGOING + "/good.xml"))
            .thenReturn(new ByteArrayInputStream("<xml/>".getBytes()));
        when(storageService.load(ONGOING + "/good.pdf"))
            .thenReturn(new ByteArrayInputStream(fakePdfBytes()));
        when(storageService.read(ONGOING + "/good.pdf"))
            .thenReturn(new DataFile(null, ONGOING + "/good.pdf"));

        // bad.xml → parse KO
        when(storageService.load(ONGOING + "/bad.xml"))
            .thenReturn(new ByteArrayInputStream("<xml/>".getBytes()));
        when(storageService.read(ONGOING + "/bad.pdf"))
            .thenReturn(new DataFile(null, ONGOING + "/bad.pdf"));

        when(parsingService.parse(any()))
            .thenReturn(buildParsedFile())
            .thenThrow(new FileParsingException("fail"));

        when(archivingService.send(any()))
            .thenReturn(Mono.just(new ArchiverInternalInfo("ctx", "doc")));

        useCase.sendFiles();

        ArgumentCaptor<DataFile> captor = ArgumentCaptor.forClass(DataFile.class);
        verify(storageService, times(4)).move(any(), captor.capture());
        assertThat(captor.getAllValues())
            .anyMatch(df -> df.getPath().startsWith(SENT))
            .anyMatch(df -> df.getPath().startsWith(REJECTED));
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
        return "%PDF-1.4\nfake content\n%%EOF"
            .getBytes(StandardCharsets.UTF_8);
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
