package com.bnpparibas.itg.ps.ap28004.archiving.appli.usecase;

import com.bnpparibas.itg.ps.ap28004.archiving.domain.exception.DocStorageException;
import com.bnpparibas.itg.ps.ap28004.archiving.domain.model.DataFile;
import com.bnpparibas.itg.ps.ap28004.archiving.domain.port.ArchivingService;
import com.bnpparibas.itg.ps.ap28004.archiving.domain.port.ParsingService;
import com.bnpparibas.itg.ps.ap28004.archiving.domain.port.StorageService;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TarDomainAcquisitionUseCaseTest {

    @Mock StorageService   storageService;
    @Mock ParsingService   parsingService;
    @Mock ArchivingService archivingService;

    TarDomainAcquisitionUseCase useCase;

    static final String ONGOING  = "ongoing";
    static final String IN_DIR   = "indic";
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

    // ================================================================
    //  receivedFiles()
    // ================================================================

    @Test
    void receivedFiles_emptyList_noLoadNorWrite() throws Exception {
        when(storageService.listFilePaths(IN_DIR, MAX)).thenReturn(List.of());

        assertDoesNotThrow(() -> useCase.receivedFiles());

        verify(storageService, never()).load(any());
        verify(storageService, never()).write(any());
    }

    @Test
    void receivedFiles_indEntry_writesFileToOngoing() throws Exception {
        byte[] tarGz = buildTarGz("IND/document.xml", "xml-content".getBytes());
        when(storageService.listFilePaths(IN_DIR, MAX)).thenReturn(List.of("path/arc.tar.gz"));
        when(storageService.load("path/arc.tar.gz")).thenReturn(new ByteArrayInputStream(tarGz));

        assertDoesNotThrow(() -> useCase.receivedFiles());

        verify(storageService).write(any(DataFile.class));
        verify(storageService).delete("path/arc.tar.gz");
    }

    @Test
    void receivedFiles_pdfEntry_writesFileToOngoing() throws Exception {
        byte[] tarGz = buildTarGz("PDF/document.pdf", "pdf-content".getBytes());
        when(storageService.listFilePaths(IN_DIR, MAX)).thenReturn(List.of("path/arc.tar.gz"));
        when(storageService.load("path/arc.tar.gz")).thenReturn(new ByteArrayInputStream(tarGz));

        assertDoesNotThrow(() -> useCase.receivedFiles());

        verify(storageService).write(any(DataFile.class));
        verify(storageService).delete("path/arc.tar.gz");
    }

    @Test
    void receivedFiles_directoryEntry_skippedNoWrite() throws Exception {
        byte[] tarGz = buildTarGzDirectory("SUBDIR/");
        when(storageService.listFilePaths(IN_DIR, MAX)).thenReturn(List.of("path/arc.tar.gz"));
        when(storageService.load("path/arc.tar.gz")).thenReturn(new ByteArrayInputStream(tarGz));

        assertDoesNotThrow(() -> useCase.receivedFiles());

        verify(storageService, never()).write(any());
        verify(storageService).delete("path/arc.tar.gz");
    }

    @Test
    void receivedFiles_unknownPrefix_notWritten() throws Exception {
        byte[] tarGz = buildTarGz("OTHER/file.txt", "data".getBytes());
        when(storageService.listFilePaths(IN_DIR, MAX)).thenReturn(List.of("path/arc.tar.gz"));
        when(storageService.load("path/arc.tar.gz")).thenReturn(new ByteArrayInputStream(tarGz));

        assertDoesNotThrow(() -> useCase.receivedFiles());

        verify(storageService, never()).write(any());
        verify(storageService).delete("path/arc.tar.gz");
    }

    @Test
    void receivedFiles_exceptionOnLoad_catchedAndLogged() throws Exception {
        when(storageService.listFilePaths(IN_DIR, MAX)).thenReturn(List.of("bad.tar.gz"));
        when(storageService.load("bad.tar.gz")).thenThrow(new RuntimeException("io error"));

        assertDoesNotThrow(() -> useCase.receivedFiles());

        verify(storageService, never()).write(any());
        verify(storageService, never()).delete(any());
    }

    // ================================================================
    //  sendFiles()
    // ================================================================

    @Test
    void sendFiles_emptyList_doesNothing() throws Exception {
        when(storageService.list(ONGOING, MAX)).thenReturn(List.of());

        assertDoesNotThrow(() -> useCase.sendFiles());
    }

    @Test
    void sendFiles_nonXmlFile_filteredOut() throws Exception {
        DataFile pdfFile = mock(DataFile.class);
        when(pdfFile.getPath()).thenReturn(ONGOING + "/file.pdf");
        when(storageService.list(ONGOING, MAX)).thenReturn(List.of(pdfFile));

        assertDoesNotThrow(() -> useCase.sendFiles());

        verify(storageService, never()).write(any());
    }

    // ================================================================
    //  moveInError()
    // ================================================================

    @Test
    void moveInError_happyPath_movesXmlThenPdfToRejected() throws Exception {
        DataFile file = mock(DataFile.class);
        when(file.getPath()).thenReturn(ONGOING + "/file.xml");
        when(file.getTags()).thenReturn(null);

        useCase.moveInError(file);

        verify(storageService).move(eq(ONGOING + "/file.xml"), anyString(), isNull());
        verify(storageService).move(eq(ONGOING + "/file.pdf"), anyString(), isNull());
        verify(storageService, times(2)).move(anyString(), anyString(), any());
    }

    @Test
    void moveInError_firstMoveThrows_catchedAndLogged() throws Exception {
        DataFile file = mock(DataFile.class);
        when(file.getPath()).thenReturn(ONGOING + "/file.xml");
        when(file.getTags()).thenReturn(null);
        doThrow(new DocStorageException("move failed"))
                .when(storageService).move(anyString(), anyString(), any());

        assertDoesNotThrow(() -> useCase.moveInError(file));

        // Exception au 1er move → le 2ème n'est jamais appelé
        verify(storageService, times(1)).move(anyString(), anyString(), any());
    }

    // ================================================================
    //  moveSentDir()
    // ================================================================

    @Test
    void moveSentDir_happyPath_movesXmlThenPdfToSent() throws Exception {
        DataFile file = mock(DataFile.class);
        when(file.getPath()).thenReturn(ONGOING + "/file.xml");
        when(file.getTags()).thenReturn(null);

        useCase.moveSentDir(file);

        verify(storageService).move(eq(ONGOING + "/file.xml"), anyString(), isNull());
        verify(storageService).move(eq(ONGOING + "/file.pdf"), anyString(), isNull());
        verify(storageService, times(2)).move(anyString(), anyString(), any());
    }

    @Test
    void moveSentDir_firstMoveThrows_catchedAndLogged() throws Exception {
        DataFile file = mock(DataFile.class);
        when(file.getPath()).thenReturn(ONGOING + "/file.xml");
        when(file.getTags()).thenReturn(null);
        doThrow(new DocStorageException("move failed"))
                .when(storageService).move(anyString(), anyString(), any());

        assertDoesNotThrow(() -> useCase.moveSentDir(file));

        verify(storageService, times(1)).move(anyString(), anyString(), any());
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private byte[] buildTarGz(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bos);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry entry = new TarArchiveEntry(entryName);
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return bos.toByteArray();
    }

    private byte[] buildTarGzDirectory(String dirName) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bos);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry dir = new TarArchiveEntry(dirName);
            tar.putArchiveEntry(dir);
            tar.closeArchiveEntry();
        }
        return bos.toByteArray();
    }
}
