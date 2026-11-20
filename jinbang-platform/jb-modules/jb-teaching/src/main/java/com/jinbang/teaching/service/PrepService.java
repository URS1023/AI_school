package com.jinbang.teaching.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.ai.CoursewareGeneratedEvent;
import com.jinbang.api.resource.ResourceApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.storage.FileStorage;
import com.jinbang.teaching.domain.TcMaterial;
import com.jinbang.teaching.domain.TcMaterialVersion;
import com.jinbang.teaching.domain.TcPrepCatalog;
import com.jinbang.teaching.mapper.TcMaterialMapper;
import com.jinbang.teaching.mapper.TcMaterialVersionMapper;
import com.jinbang.teaching.mapper.TcPrepCatalogMapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 独立备课（PRD 4.1）：目录树、资料（上传 / 新建 / 找资源 / AI 生成）、版本、回收站。资料归个人。 */
@Service
@RequiredArgsConstructor
public class PrepService {
    public static final String AI_FOLDER = "AI 生成";

    private final TcPrepCatalogMapper catalogMapper;
    private final TcMaterialMapper materialMapper;
    private final TcMaterialVersionMapper versionMapper;
    private final FileStorage storage;
    private final ResourceApi resourceApi;

    // ---------------- 目录 ----------------

    public List<TcPrepCatalog> catalogs(Long userId, String period, String subject) {
        return catalogMapper.selectList(Wrappers.<TcPrepCatalog>lambdaQuery().eq(TcPrepCatalog::getOwnerUserId, userId)
                .eq(period != null, TcPrepCatalog::getPeriod, period).eq(subject != null, TcPrepCatalog::getSubject, subject)
                .orderByAsc(TcPrepCatalog::getParentId).orderByAsc(TcPrepCatalog::getSortNo).orderByAsc(TcPrepCatalog::getId));
    }

    public TcPrepCatalog createCatalog(Long userId, Long parentId, String name, String period, String subject, String edition) {
        BizException.throwIf(name == null || name.isBlank(), ErrorCode.BAD_REQUEST, "目录名称不能为空");
        if (parentId != null && parentId != 0) {
            ownCatalog(userId, parentId);
        }
        TcPrepCatalog c = new TcPrepCatalog();
        c.setOwnerUserId(userId);
        c.setParentId(parentId == null ? 0L : parentId);
        c.setCatalogName(name.trim());
        c.setPeriod(period);
        c.setSubject(subject);
        c.setEdition(edition);
        c.setSortNo(0);
        catalogMapper.insert(c);
        return c;
    }

    /** 按教材 / 课程批量生成目录（createByBooks / createByCourses）：一层教材，下面是各课。 */
    @Transactional
    public TcPrepCatalog createByBook(Long userId, String bookName, List<String> courses, String period, String subject,
                                      String edition) {
        TcPrepCatalog book = createCatalog(userId, 0L, bookName, period, subject, edition);
        int i = 0;
        for (String c : courses) {
            TcPrepCatalog child = createCatalog(userId, book.getId(), c, period, subject, edition);
            child.setSortNo(++i);
            catalogMapper.updateById(child);
        }
        return book;
    }

    public void renameOrMoveCatalog(Long userId, Long id, String name, Long newParent) {
        TcPrepCatalog c = ownCatalog(userId, id);
        if (name != null && !name.isBlank()) {
            c.setCatalogName(name.trim());
        }
        if (newParent != null) {
            BizException.throwIf(newParent.equals(id), ErrorCode.BAD_REQUEST, "不能移动到自己下面");
            if (newParent != 0) {
                ownCatalog(userId, newParent);
            }
            c.setParentId(newParent);
        }
        catalogMapper.updateById(c);
    }

    /** 删除目录：目录下的资料进回收站。 */
    @Transactional
    public void deleteCatalog(Long userId, Long id) {
        ownCatalog(userId, id);
        BizException.throwIf(catalogMapper.exists(Wrappers.<TcPrepCatalog>lambdaQuery().eq(TcPrepCatalog::getParentId, id)),
                ErrorCode.CONFLICT, "请先删除子目录");
        materialMapper.update(Wrappers.<TcMaterial>lambdaUpdate().set(TcMaterial::getDeleted, true)
                .set(TcMaterial::getDeletedAt, LocalDateTime.now()).eq(TcMaterial::getCatalogId, id)
                .eq(TcMaterial::getOwnerUserId, userId));
        catalogMapper.deleteById(id);
    }

    // ---------------- 资料 ----------------

    public List<TcMaterial> materials(Long userId, Long catalogId, String fileType, boolean recycle) {
        return materialMapper.selectList(Wrappers.<TcMaterial>lambdaQuery().eq(TcMaterial::getOwnerUserId, userId)
                .eq(TcMaterial::getDeleted, recycle).eq(catalogId != null, TcMaterial::getCatalogId, catalogId)
                .eq(fileType != null, TcMaterial::getFileType, fileType).orderByDesc(TcMaterial::getCreatedAt));
    }

    public TcMaterial upload(Long userId, Long catalogId, String fileName, byte[] bytes) {
        if (catalogId != null) {
            ownCatalog(userId, catalogId);
        }
        String ext = ext(fileName);
        return add(userId, catalogId, fileName, typeOf(ext), storage.put("prep", ext, bytes), bytes.length, "UPLOAD", null);
    }

    /** 新建空白 PPT / Word。 */
    public TcMaterial createBlank(Long userId, Long catalogId, String name, String type) {
        if (catalogId != null) {
            ownCatalog(userId, catalogId);
        }
        byte[] bytes;
        String ext;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if ("PPTX".equalsIgnoreCase(type)) {
                try (XMLSlideShow ppt = new XMLSlideShow()) {
                    ppt.createSlide();
                    ppt.write(out);
                }
                ext = "pptx";
            } else {
                try (XWPFDocument doc = new XWPFDocument()) {
                    doc.createParagraph();
                    doc.write(out);
                }
                ext = "docx";
            }
            bytes = out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String fileName = (name == null || name.isBlank() ? "新建文件" : name) + "." + ext;
        return add(userId, catalogId, fileName, typeOf(ext), storage.put("prep", ext, bytes), bytes.length, "BLANK", null);
    }

    /** 找资源：平台资源取进当前目录（按资源下载规则校验和扣费）。 */
    public TcMaterial fromResource(CurrentIdentity ci, Long catalogId, Long resourceId) {
        if (catalogId != null) {
            ownCatalog(ci.userId(), catalogId);
        }
        ResourceApi.ResourceFile f = resourceApi.takeForPrep(resourceId);
        byte[] bytes = storage.get(f.storageKey());
        String ext = f.fileExt() == null ? "" : f.fileExt();
        return add(ci.userId(), catalogId, f.title() + (ext.isEmpty() ? "" : "." + ext), typeOf(ext),
                storage.put("prep", ext, bytes), bytes.length, "RESOURCE", String.valueOf(resourceId));
    }

    public TcMaterial add(Long userId, Long catalogId, String fileName, String fileType, String key, long size, String source,
                          String ref) {
        TcMaterial m = new TcMaterial();
        m.setOwnerUserId(userId);
        m.setCatalogId(catalogId);
        m.setFileName(fileName);
        m.setFileType(fileType);
        m.setStorageKey(key);
        m.setSizeBytes(size);
        m.setSource(source);
        m.setSourceRef(ref);
        m.setVersionNo(1);
        m.setDeleted(false);
        materialMapper.insert(m);
        snapshot(m);
        return m;
    }

    /** 保存新版本（在线编辑自动保存），旧版本保留可恢复。 */
    @Transactional
    public TcMaterial saveVersion(Long userId, Long id, byte[] bytes) {
        TcMaterial m = own(userId, id);
        m.setStorageKey(storage.put("prep", ext(m.getFileName()), bytes));
        m.setSizeBytes((long) bytes.length);
        m.setVersionNo(m.getVersionNo() + 1);
        materialMapper.updateById(m);
        snapshot(m);
        return m;
    }

    public List<TcMaterialVersion> versions(Long userId, Long id) {
        own(userId, id);
        return versionMapper.selectList(Wrappers.<TcMaterialVersion>lambdaQuery().eq(TcMaterialVersion::getMaterialId, id)
                .orderByDesc(TcMaterialVersion::getVersionNo));
    }

    @Transactional
    public TcMaterial restoreVersion(Long userId, Long id, int versionNo) {
        TcMaterial m = own(userId, id);
        TcMaterialVersion v = BizException.notNull(versionMapper.selectOne(Wrappers.<TcMaterialVersion>lambdaQuery()
                .eq(TcMaterialVersion::getMaterialId, id).eq(TcMaterialVersion::getVersionNo, versionNo)), "版本");
        return saveVersion(userId, m.getId(), storage.get(v.getStorageKey()));
    }

    public TcMaterial update(Long userId, Long id, String name, Long catalogId) {
        TcMaterial m = own(userId, id);
        if (name != null && !name.isBlank()) {
            m.setFileName(name.contains(".") ? name : name + "." + ext(m.getFileName()));
        }
        if (catalogId != null) {
            ownCatalog(userId, catalogId);
            m.setCatalogId(catalogId);
        }
        materialMapper.updateById(m);
        return m;
    }

    public TcMaterial copy(Long userId, Long id) {
        TcMaterial m = own(userId, id);
        byte[] bytes = storage.get(m.getStorageKey());
        String ext = ext(m.getFileName());
        String base = m.getFileName().contains(".") ? m.getFileName().substring(0, m.getFileName().lastIndexOf('.')) : m.getFileName();
        return add(userId, m.getCatalogId(), base + " - 副本." + ext, m.getFileType(), storage.put("prep", ext, bytes),
                bytes.length, "COPY", String.valueOf(id));
    }

    public void recycle(Long userId, Long id, boolean deleted) {
        TcMaterial m = materialMapper.selectById(id);
        if (m == null || !m.getOwnerUserId().equals(userId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "资料不存在");
        }
        m.setDeleted(deleted);
        m.setDeletedAt(deleted ? LocalDateTime.now() : null);
        materialMapper.updateById(m);
    }

    /** 回收站彻底删除。 */
    @Transactional
    public int purge(Long userId) {
        List<TcMaterial> list = materials(userId, null, null, true);
        for (TcMaterial m : list) {
            versionMapper.selectList(Wrappers.<TcMaterialVersion>lambdaQuery().eq(TcMaterialVersion::getMaterialId, m.getId()))
                    .forEach(v -> storage.delete(v.getStorageKey()));
            versionMapper.delete(Wrappers.<TcMaterialVersion>lambdaQuery().eq(TcMaterialVersion::getMaterialId, m.getId()));
            materialMapper.deleteById(m.getId());
        }
        return list.size();
    }

    public TcMaterial own(Long userId, Long id) {
        TcMaterial m = materialMapper.selectById(id);
        if (m == null || !m.getOwnerUserId().equals(userId) || Boolean.TRUE.equals(m.getDeleted())) {
            throw new BizException(ErrorCode.NOT_FOUND, "资料不存在");
        }
        return m;
    }

    /** AI 平台生成的课件自动进入独立备课（PRD 7.4 ➕P0）：指定目录或「AI 生成」目录。 */
    @EventListener
    public void onCourseware(CoursewareGeneratedEvent e) {
        Long catalogId = e.targetCatalogId();
        if (catalogId != null) {
            TcPrepCatalog c = catalogMapper.selectById(catalogId);
            if (c == null || !c.getOwnerUserId().equals(e.userId())) {
                catalogId = null;
            }
        }
        if (catalogId == null) {
            catalogId = aiFolder(e.userId()).getId();
        }
        add(e.userId(), catalogId, e.title() + "." + e.fileType().toLowerCase(Locale.ROOT), e.fileType(), e.storageKey(),
                e.sizeBytes(), "PPTX".equals(e.fileType()) ? "AI_PPT" : "AI_PLAN", e.taskId());
    }

    public TcPrepCatalog aiFolder(Long userId) {
        return catalogMapper.selectList(Wrappers.<TcPrepCatalog>lambdaQuery().eq(TcPrepCatalog::getOwnerUserId, userId)
                        .eq(TcPrepCatalog::getParentId, 0L).eq(TcPrepCatalog::getCatalogName, AI_FOLDER)).stream().findFirst()
                .orElseGet(() -> createCatalog(userId, 0L, AI_FOLDER, null, null, null));
    }

    public TcMaterial findBySourceRef(Long userId, String ref) {
        return materialMapper.selectList(Wrappers.<TcMaterial>lambdaQuery().eq(TcMaterial::getOwnerUserId, userId)
                .eq(TcMaterial::getSourceRef, ref)).stream().findFirst().orElse(null);
    }

    private void snapshot(TcMaterial m) {
        TcMaterialVersion v = new TcMaterialVersion();
        v.setMaterialId(m.getId());
        v.setVersionNo(m.getVersionNo());
        v.setStorageKey(m.getStorageKey());
        v.setSizeBytes(m.getSizeBytes());
        versionMapper.insert(v);
    }

    private TcPrepCatalog ownCatalog(Long userId, Long id) {
        TcPrepCatalog c = catalogMapper.selectById(id);
        if (c == null || !Objects.equals(c.getOwnerUserId(), userId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "目录不存在");
        }
        return c;
    }

    static String ext(String name) {
        return name != null && name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
    }

    static String typeOf(String ext) {
        return switch (ext) {
            case "ppt", "pptx" -> "PPTX";
            case "doc", "docx" -> "DOCX";
            case "pdf" -> "PDF";
            case "mp4", "mov", "avi" -> "VIDEO";
            default -> "OTHER";
        };
    }
}
