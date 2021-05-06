package com.fpt.edu.schedule.service.impl;

import com.fpt.edu.schedule.common.constant.Config;
import com.fpt.edu.schedule.common.enums.Role;
import com.fpt.edu.schedule.common.enums.StatusReport;
import com.fpt.edu.schedule.common.event.Mail;
import com.fpt.edu.schedule.common.exception.InvalidRequestException;
import com.fpt.edu.schedule.dto.TimetableDetailDTO;
import com.fpt.edu.schedule.dto.TimetableEdit;
import com.fpt.edu.schedule.model.*;
import com.fpt.edu.schedule.repository.base.*;
import com.fpt.edu.schedule.service.base.*;
import lombok.AllArgsConstructor;
import org.apache.commons.io.FilenameUtils;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.transaction.Transactional;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class RequestServiceImpl implements RequestService {
    RoomService roomService;
    ClassNameService classNameService;
    ClassNameRepository classNameRepository;
    SubjectService subjectService;
    TimeTableDetailService timeTableDetailService;
    TimetableDetailRepository timetableDetailRepo;
    TimetableRepository timetableRepo;
    SlotService slotService;
    RequestRepository requestRepository;
    LecturerRepository lecturerRepo;
    SemesterRepository semesterRepo;
    RoomRepository roomRepo;
    LecturerService lecturerService;
    DepartmentRepository departmentRepository;
    ExpectedRepository expectedRepo;
    RoleService roleService;
    ConfirmationRepository confirmationRepos;

    @Autowired
    ApplicationEventPublisher mailEventPublisher;

    @Transactional
    @Override
    public void generateExcelFile(MultipartFile multipartFile, int semesterId, String hodGoogleId) {
        try {
            Lecturer lecturer = lecturerService.findByGoogleId(hodGoogleId);
            bindDataFromFile(multipartFile, lecturer);
            saveTimetable(multipartFile, semesterId, lecturer, false);

        } catch (Exception e) {
            throw new InvalidRequestException(e.getMessage());
        }
    }

    @Transactional
    @Override
    public void generateAdditionalExcelFile(MultipartFile multipartFile, int semesterId, String hodGoogleId) {
        try {
            Lecturer lecturer = lecturerService.findByGoogleId(hodGoogleId);
            bindDataFromFile(multipartFile, lecturer);
            saveTimetable(multipartFile, semesterId, lecturer, true);

        } catch (Exception e) {
            throw new InvalidRequestException(e.getMessage());
        }
    }

    @Override
    public Request addRequest(Request request, String lecturerId) {

        request.setSemester(semesterRepo.getAllByNowIsTrue());
        request.setCreatedDate(new Date());
        request.setStatus(StatusReport.PENDING);
        Lecturer lecturer = lecturerService.findByGoogleId(lecturerId);
        Lecturer hod = lecturerRepo.findAllByDepartmentAndRole(lecturer.getDepartment(), roleService.getRoleByName(Role.ROLE_ADMIN.getName()));
        request.setLecturer(lecturer);
        String title = "[DSTT SYSTEM] New Request";
        String content = String.format("Lecturer: %s \nRequest: %s\n\n\nPlease visit %s to view response !", lecturer.getEmail(), request.getContent(), Config.domainWebsite);
        mailEventPublisher.publishEvent(new Mail(this, content, Arrays.asList(hod.getEmail()), title));
        return requestRepository.save(request);
    }

    @Override
    public Request updateRequest(Request request, String lecturerId) {
        Lecturer hod = lecturerService.findByGoogleId(lecturerId);
        Request existedRequest = requestRepository.findReportById(request.getId());
        if (existedRequest == null) {
            throw new InvalidRequestException("Don't find this report !");
        }
        existedRequest.setStatus(request.getStatus());
        existedRequest.setReplyContent(request.getReplyContent());
        String content = String.format("Lecturer: %s response your request: %s\n\nReply: %s\nStatus: %s\n\nPlease visit %s to view !"
                , hod.getEmail(), existedRequest.getContent(), existedRequest.getReplyContent(), existedRequest.getStatus(), Config.domainWebsite);
        String title = "[DSTT SYSTEM] Response Request";
        mailEventPublisher.publishEvent(new Mail(this, content, Arrays.asList(existedRequest.getLecturer().getEmail()), title));
        return requestRepository.save(existedRequest);
    }

    @Override
    public void removeRequestById(int id) {
        Request existedRequest = requestRepository.findReportById(id);
        if (existedRequest == null) {
            throw new InvalidRequestException(String.format("Don't find this report reportId:%s", id));
        }
        requestRepository.removeById(id);
    }

    @Override
    public QueryParam.PagedResultSet<Request> findByCriteria(QueryParam queryParam) {
        QueryParam.PagedResultSet page = new QueryParam.PagedResultSet();
        BaseSpecifications cns = new BaseSpecifications(queryParam);
        page.setTotalCount((int) requestRepository.count(cns));
        if (queryParam.getPage() < 1) {
            queryParam.setPage(1);
            queryParam.setLimit(1000);
        }
        Page<Lecturer> requests = requestRepository.findAll(cns, PageRequest.of(queryParam.getPage() - 1, queryParam.getLimit()
                , Sort.by(queryParam.isAscending() ? Sort.Direction.ASC : Sort.Direction.DESC, queryParam.getSortField())));
        page.setPage(queryParam.getPage());
        page.setLimit(queryParam.getLimit());
        page.setSize(requests.getContent().size());
        page.setResults(requests.getContent());
        return page;
    }


    @Override
    public ByteArrayInputStream exportFile(int semesterId, String groupBy) {
        HSSFWorkbook workbook = new HSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Semester se = semesterRepo.findById(semesterId);
        HSSFSheet sheet = workbook.createSheet(se.getSeason() + " " + se.getYear());
        List<TimetableDetail> list = timetableRepo.findBySemesterAndTempFalse(se).getTimetableDetails();
        int rowNumber = 0;
        Cell cell;
        Row row;

        //group by line
        if (groupBy.equals("line")) {
            list.sort(Comparator.comparing(TimetableDetail::getLineId));
            row = sheet.createRow(rowNumber);
            // Class
            cell = row.createCell(0, CellType.STRING);
            cell.setCellValue("Class");
            // EmpName
            cell = row.createCell(1, CellType.STRING);
            cell.setCellValue("Subject");

            // Salary
            cell = row.createCell(2, CellType.STRING);
            cell.setCellValue("Slot");

            // Grade
            cell = row.createCell(3, CellType.STRING);
            cell.setCellValue("Dept");

            // Bonus
            cell = row.createCell(4, CellType.STRING);
            cell.setCellValue("Room");
            cell = row.createCell(5, CellType.STRING);
            cell.setCellValue("Lecturer");


            // Data
            for (TimetableDetail emp : list) {
                rowNumber++;
                row = sheet.createRow(rowNumber);

                // EmpNo (A)
                cell = row.createCell(0, CellType.STRING);
                cell.setCellValue(emp.getClassName().getName());
                // EmpName (B)
                cell = row.createCell(1, CellType.STRING);
                cell.setCellValue(emp.getSubject().getCode());
                // Salary (C)
                cell = row.createCell(2, CellType.STRING);
                cell.setCellValue(emp.getSlot().getName());
                // Grade (D)
                cell = row.createCell(3, CellType.STRING);
                cell.setCellValue(emp.getSubject().getDepartment());
                // Bonus (E)

                cell = row.createCell(4, CellType.STRING);
                cell.setCellValue(emp.getRoom() != null ? emp.getRoom().getName() : "");
                cell = row.createCell(5, CellType.STRING);
                cell.setCellValue(emp.getLecturer() != null ? emp.getLecturer().getShortName() : "");
            }
        }

        // grouop by teacher
        else {
            CellStyle cs = workbook.createCellStyle();
            cs.setWrapText(true);
            List<TimetableDetailDTO> timetableDetailDTOS = list.stream()
                    .distinct()
                    .map(i -> new TimetableDetailDTO(i.getId(),
                            i.getLecturer() != null ? i.getLecturer().getShortName() : " NOT_ASSIGN",
                            i.getRoom() != null ? i.getRoom().getName() : "NOT_ASSIGN",
                            i.getClassName().getName(), i.getSlot().getName(), i.getSubject().getCode(), i.getTimetableStatus(), i.getReason()))
                    .collect(Collectors.toList());
            Map<String, List<TimetableDetailDTO>> collect = timetableDetailDTOS.stream().collect(Collectors.groupingBy(TimetableDetailDTO::getLecturerShortName));
            List<TimetableEdit> timetableEdits = collect
                    .entrySet()
                    .stream()
                    .map(i -> new TimetableEdit(i.getKey(), i.getValue())).collect(Collectors.toList());
            timetableEdits.sort(Comparator.comparing(TimetableEdit::getRoom).reversed());
            row = sheet.createRow(rowNumber);
            cell = row.createCell(1, CellType.STRING);
            cell.setCellValue("M1");
            cell = row.createCell(2, CellType.STRING);
            cell.setCellValue("M2");
            cell = row.createCell(3, CellType.STRING);
            cell.setCellValue("M3");
            cell = row.createCell(4, CellType.STRING);
            cell.setCellValue("E1");
            cell = row.createCell(5, CellType.STRING);
            cell.setCellValue("E2");
            cell = row.createCell(6, CellType.STRING);
            cell.setCellValue("E3");
            cell = row.createCell(7, CellType.STRING);
            cell.setCellValue("M4");
            cell = row.createCell(8, CellType.STRING);
            cell.setCellValue("M5");
            cell = row.createCell(9, CellType.STRING);
            cell.setCellValue("E4");
            cell = row.createCell(10, CellType.STRING);
            cell.setCellValue("E5");

//

            // Data
            for (TimetableEdit time : timetableEdits) {
                rowNumber++;
                row = sheet.createRow(rowNumber);

                // EmpNo (A)
                cell = row.createCell(0, CellType.STRING);
                cell.setCellValue(time.getRoom());
                // EmpName (B)
                for (TimetableDetailDTO t : time.getTimetable()) {
                    if (t.getSlot().equals("M1")) {
                        cell = row.createCell(1, CellType.STRING);
                        setContentForCell(cell, t, cs);
                    }
                    if (t.getSlot().equals("M2")) {
                        cell = row.createCell(2, CellType.STRING);
                        setContentForCell(cell, t, cs);
                    }
                    if (t.getSlot().equals("M3")) {
                        cell = row.createCell(3, CellType.STRING);
                        setContentForCell(cell, t, cs);
                    }
                    if (t.getSlot().equals("E1")) {
                        cell = row.createCell(4, CellType.STRING);
                        setContentForCell(cell, t, cs);
                    }
                    if (t.getSlot().equals("E2")) {
                        cell = row.createCell(5, CellType.STRING);
                        setContentForCell(cell, t, cs);
                    }
                    if (t.getSlot().equals("E3")) {
                        cell = row.createCell(6, CellType.STRING);
                        setContentForCell(cell, t, cs);
                    }
                    if (t.getSlot().equals("M4")) {
                        cell = row.createCell(7, CellType.STRING);
                        setContentForCell(cell, t, cs);
                    }
                    if (t.getSlot().equals("M5")) {
                        cell = row.createCell(8, CellType.STRING);
                        setContentForCell(cell, t, cs);
                    }
                    if (t.getSlot().equals("E4")) {
                        cell = row.createCell(9, CellType.STRING);
                        setContentForCell(cell, t, cs);
                    }
                    if (t.getSlot().equals("E5")) {
                        cell = row.createCell(10, CellType.STRING);
                        setContentForCell(cell, t, cs);
                    }
                    // Salary (C)
                }
            }
        }
        try {
            workbook.write(out);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return new ByteArrayInputStream(out.toByteArray());
    }

    private void setContentForCell(Cell cell, TimetableDetailDTO t, CellStyle cs) {
        cell.setCellValue(t.getRoom() + "\n" + t.getSubjectCode() + "\n" + t.getClassName());
        cell.setCellStyle(cs);
    }

    void saveTimetable(MultipartFile multipartFile, int semesterId, Lecturer lecturer, boolean isAdditional) throws IOException {
            Semester se = semesterRepo.findById(semesterId);
            XSSFWorkbook workbook = new XSSFWorkbook(multipartFile.getInputStream());
            XSSFSheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();
            rowIterator.next();
            List<Timetable> existedTimetable = timetableRepo.findAllBySemester(se);

            if (existedTimetable.size() > 0 && !isAdditional) {

                existedTimetable.stream().forEach(i -> {
                    timetableDetailRepo.deleteAllByTimetable(i.getId());
                    timetableRepo.deleteById(i.getId());
                });
                confirmationRepos.deleteAllBySemester(se);

            }
            Timetable timeTable = new Timetable();
            Timetable timeTableTemp = new Timetable();
            timeTableTemp.setTemp(true);
            int line = 0;
            int lineTemp = 0;
            if (isAdditional) {
                timeTable = timetableRepo.findBySemesterAndTempFalse(semesterRepo.findById(semesterId));
                if(timeTable == null){
                    throw new InvalidRequestException("Please upload data of semester first!");
                }
                timeTableTemp = timetableRepo.findBySemesterAndTempTrue(semesterRepo.findById(semesterId));
                TimetableDetail timetableDetail = timetableDetailRepo.findFirstByTimetableOrderByLineIdDesc(timeTable);
                line = timetableDetail.getLineId();
                lineTemp = timetableDetail.getLineId();
            }
            while (rowIterator.hasNext()) {
                int column = 0;

                Row row = rowIterator.next();
                if (isSkip(row, lecturer)) {
                    continue;
                }
                Iterator<Cell> cellIterator = row.cellIterator();
                TimetableDetail timetableDetail = new TimetableDetail();
                TimetableDetail timetableDetailTemp = new TimetableDetail();
                timeTable.setSemester(semesterRepo.findById(semesterId));
                timeTableTemp.setSemester(semesterRepo.findById(semesterId));
                line++;
                lineTemp++;
                while (cellIterator.hasNext()) {
                    column++;
                    Cell cell = cellIterator.next();

                    if (cell.getCellTypeEnum().equals(CellType.BLANK)) {
                        break;
                    }
                    switch (column) {
                        case 1:
                            timetableDetail.setClassName(classNameRepository.findByName(cell.getStringCellValue().trim()));
                            timetableDetailTemp.setClassName(classNameRepository.findByName(cell.getStringCellValue().trim()));

                            break;
                        case 2:
                            timetableDetail.setSubject(subjectService.getSubjectByCode(cell.getStringCellValue().trim()));
                            timetableDetailTemp.setSubject(subjectService.getSubjectByCode(cell.getStringCellValue().trim()));

                            break;
                        case 3:
                            timetableDetail.setSlot(slotService.getSlotByName(cell.getStringCellValue().trim()));
                            timetableDetailTemp.setSlot(slotService.getSlotByName(cell.getStringCellValue().trim()));

                            break;
                        case 5:
                            timetableDetail.setRoom(roomService.getRoomByName(cell.getStringCellValue().trim()));
                            timetableDetailTemp.setRoom(roomService.getRoomByName(cell.getStringCellValue().trim()));
                            break;
                    }
                    timetableDetail.setTimetable(timeTable);
                    timetableDetail.setLineId(line);
                    timeTable.getTimetableDetails().add(timetableDetail);


                    timetableDetailTemp.setTimetable(timeTableTemp);
                    timetableDetailTemp.setLineId(lineTemp);
                    timeTableTemp.getTimetableDetails().add(timetableDetailTemp);
                }
            }
            timetableRepo.save(timeTableTemp);
            timetableRepo.save(timeTable);
    }

    private boolean isSkip(Row row, Lecturer lecturer) {
        Cell cell = row.getCell(6);
        if (!row.getCell(3).getStringCellValue().equalsIgnoreCase(lecturer.getDepartment()) || cell != null) {
            return true;
        }
        return false;
    }

    private void bindDataFromFile(MultipartFile multipartFile, Lecturer lecturer) throws IOException {
        String extension = FilenameUtils.getExtension(multipartFile.getOriginalFilename());
        if (!extension.contains("xlsx")) {
            throw new InvalidRequestException("Wrong file format!");
        }
        XSSFWorkbook workbook = new XSSFWorkbook(multipartFile.getInputStream());
        XSSFSheet sheet = workbook.getSheetAt(0);
        Iterator<Row> rowIterator = sheet.iterator();
        rowIterator.next();
        while (rowIterator.hasNext()) {
            int column = 0;
            Row row = rowIterator.next();
            Iterator<Cell> cellIterator = row.cellIterator();
            if (isSkip(row, lecturer)) {
                continue;
            }
            while (cellIterator.hasNext()) {
                column++;
                Cell cell = cellIterator.next();
                if (column == 6) {
                    break;
                }
                    switch (column) {
                        case 1:
                            if (classNameRepository.findByName(cell.getStringCellValue().trim()) != null) {
                                break;
                            }
                            classNameService.addClassName(new ClassName(cell.getStringCellValue().trim()));
                            break;
                        case 2:
                            if (subjectService.getSubjectByCode(cell.getStringCellValue().trim()) != null) {
                                break;
                            }
                            subjectService.addSubject(new Subject(cell.getStringCellValue().trim(), row.getCell(3).getStringCellValue()));
                            break;
                        case 4:
                            if (departmentRepository.findByName(cell.getStringCellValue().trim()) != null) {
                                break;
                            }
                            departmentRepository.save(new Department(cell.getStringCellValue().trim()));
                            break;
                        case 5:
                            if (roomRepo.findByName(cell.getStringCellValue().trim()) != null) {
                                break;
                            }
                            roomService.addRoom(new Room(cell.getStringCellValue().trim()));
                            break;
                    }

            }
        }
    }
}
