package com.fpt.edu.schedule.service.impl;


import com.fpt.edu.schedule.ai.lib.Class;
import com.fpt.edu.schedule.ai.lib.ExpectedSlot;
import com.fpt.edu.schedule.ai.lib.ExpectedSubject;
import com.fpt.edu.schedule.ai.lib.Room;
import com.fpt.edu.schedule.ai.lib.Subject;
import com.fpt.edu.schedule.ai.lib.*;
import com.fpt.edu.schedule.ai.model.*;
import com.fpt.edu.schedule.common.enums.StatusLecturer;
import com.fpt.edu.schedule.common.enums.TimetableStatus;
import com.fpt.edu.schedule.common.exception.InvalidRequestException;
import com.fpt.edu.schedule.dto.Runs;
import com.fpt.edu.schedule.dto.TimetableDetailDTO;
import com.fpt.edu.schedule.dto.TimetableProcess;
import com.fpt.edu.schedule.model.*;
import com.fpt.edu.schedule.repository.base.*;
import com.fpt.edu.schedule.service.base.SemesterService;
import com.fpt.edu.schedule.service.base.SubjectService;
import com.fpt.edu.schedule.service.base.TimetableService;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class TimetableServiceImpl implements TimetableService {
    public static final int POPULATION_SIZE = 150;

    @Autowired
    TimetableProcess timetableProcess;

    SemesterService semesterService;
    TimetableRepository timetableRepo;
    ExpectedRepository expectedRepo;
    LecturerRepository lecturerRepo;
    SubjectService subjectService;
    SubjectRepository subjectRepo;
    SlotRepository slotRepository;
    SemesterRepository semesterRepo;
    RoomRepository roomRepo;
    TimetableDetailRepository timetableDetailRepo;
    ConfirmationRepository confirmationRepo;
    @Autowired
    private ApplicationContext applicationContext;


    @Override
    public Timetable findBySemesterTempFalse(Semester semester) {
        Timetable timetable = timetableRepo.findBySemesterAndTempFalse(semester);
        if (timetable == null) {
            throw new InvalidRequestException("Don't have data for this timetable");
        }
        return timetable;
    }

    @Async
    @Override
    public void autoArrange(int semesterId, String lecturerId, GaParameter gaParameter) {
        Vector<Teacher> teachers = new Vector<>();
        Vector<Subject> subjects = new Vector<>();
        Vector<com.fpt.edu.schedule.ai.lib.Class> classes = new Vector<>();
        Vector<ExpectedSlot> expectedSlots = new Vector<>();
        Vector<ExpectedSubject> expectedSubjects = new Vector<>();
        Vector<SlotGroup> slotGroups = new Vector<>();
        Lecturer lecturer = lecturerRepo.findByGoogleId(lecturerId);
        Semester semester = semesterService.findById(semesterId);
//        importDataSUMFromFile(semester);
        Timetable timetable = timetableRepo.findBySemesterAndTempTrue(semester);
        checkGaParameter(gaParameter);
        convertData(teachers, subjects, classes, expectedSlots, expectedSubjects, semesterId, lecturerId, slotGroups, lecturer, semester, timetable);
        //To do: lay parameter info tu fe truyen vao bien gaParameter
        GeneticAlgorithm ga = applicationContext.getBean(GeneticAlgorithm.class);
        InputData inputData = new InputData(teachers, slotGroups, subjects, classes, expectedSlots, expectedSubjects, gaParameter);
        Population population = new Population(POPULATION_SIZE, inputData);
        setAttributeForGa(ga, population, inputData, lecturerId, gaParameter.getStepGeneration());
        checkExistedGa(lecturerId);
        timetableProcess.getMap().put(lecturerId, ga);
        ga.start();
    }

    @Override
    public void stop(String lecturerId) {
        timetableProcess.getMap().get(lecturerId).stop();
        System.out.println("-------------------------Stop-----LecturerId :" + lecturerId);
    }

    @Override
    public QueryParam.PagedResultSet<Runs> getGenerationInfo(String lecturerId, int page, int limit) {
        QueryParam.PagedResultSet<Runs> pagedResultSet = new QueryParam.PagedResultSet<>();
        GeneticAlgorithm ge = timetableProcess.getMap().get(lecturerId);

        pagedResultSet.setRunning(ge == null ? false : ge.isRunning());
        if (ge == null) {
            pagedResultSet.setResults(new ArrayList<>());
            pagedResultSet.setPage(page);
            return pagedResultSet;
        }
        Queue runsList = ge.getGenInfos();
        pagedResultSet.setTotalCount(runsList.size());
        List<Runs> runsListComplete = (List<Runs>) runsList.stream()
                .sorted(Comparator.comparingInt(Runs::getId))
                .skip((page - 1) * limit)
                .limit(limit)
                .collect(Collectors.toList());
        pagedResultSet.setResults(runsListComplete);
        pagedResultSet.setSize(runsListComplete.size());
        pagedResultSet.setPage(page);
        System.out.println("size-map-------------" + timetableProcess.getMap().size());
        return pagedResultSet;
    }

    @Override
    public void setDefaultTimetable(int runId, String lecturerId, int semesterId) {
        Queue<Runs> q = timetableProcess.getMap().get(lecturerId).getGenInfos();
        Runs runs = q.stream()
                .filter(i -> i.getId() == runId)
                .findAny()
                .orElse(null);
        List<TimetableDetailDTO> timetableDetails = runs.getTimetableEdit()
                .stream()
                .map(i -> i.getTimetable())
                .flatMap(List::stream)
                .collect(Collectors.toList());
        Semester semester = semesterRepo.findById(semesterId);
        Timetable timetable = timetableRepo.findBySemesterAndTempFalse(semester);
        List<Lecturer> list = getLecturersNotDraft(semester);
        timetable.getTimetableDetails().stream().forEach(x -> {
            if (!list.contains(x.getLecturer())) {
                x.setLecturer(null);
            }
        });
        Map<Integer, TimetableDetail> timetableMap = timetable.getTimetableDetails()
                .stream()
                .collect(Collectors.toMap(x -> x.getLineId(), x -> x));
        timetableDetails.stream().forEach(i -> {
            timetableMap.get(i.getLineId()).setLecturer(lecturerRepo.findByShortNameIgnoreCase(i.getLecturerShortName()));
            timetableMap.get(i.getLineId()).setRoom(roomRepo.findByName(i.getRoom()));

        });
        timetable.setTimetableDetails(new ArrayList(timetableMap.values()));
        timetableRepo.save(timetable);
    }

    private void checkExistedGa(String lecturerId) {
        if (timetableProcess.getMap().get(lecturerId) != null && !timetableProcess.getMap().get(lecturerId).isRunning()) {
            timetableProcess.getMap().remove(lecturerId);
        }
        if (timetableProcess.getMap().get(lecturerId) != null && timetableProcess.getMap().get(lecturerId).isRunning()) {
            throw new InvalidRequestException("Running arrange !");
        }
    }

    private void setAttributeForGa(GeneticAlgorithm ga, Population population, InputData inputData, String lecturerId, int step) {
        ga.setPopulation(population);
        ga.setGeneration(0);
        ga.setInputData(inputData);
        ga.setStepGen(step);
        ga.setRunning(true);
        ga.setLecturerId(lecturerId);
    }

    private void checkGaParameter(GaParameter gaParam) {
        Cofficient cofficient = gaParam.getCofficient();
        if (cofficient.getSlotCoff() + cofficient.getSubjectCoff() + cofficient.getNumberOfClassCoff() + cofficient.getDistanceCoff() + cofficient.getPartOfDayCoff() != 1) {
            throw new InvalidRequestException("Sum of slotCoff,subjectCoff,numberOfClassCoff,distanceCoff,partOfDayCoff must be equal 1!");
        }
        if (cofficient.getStdCoff() + cofficient.getSatisfactionSumCoff() != 1) {
            throw new InvalidRequestException("Sum of stdCoff,satisfactionSum must be equal 1!");
        }
        if (cofficient.getFulltimeCoff() + cofficient.getParttimeCoff() != 1) {
            throw new InvalidRequestException("Sum of fullTimeCoff,PartimeCoff must be equal 1!");
        }
        if (cofficient.getHardConstraintCoff() + cofficient.getSoftConstraintCoff() != 1) {
            throw new InvalidRequestException("Sum of hardCoff,SoftCoff must be equal 1!");
        }

    }

    private boolean isDraft(Lecturer lecturer, Semester semester) {
        Confirmation confirmation = confirmationRepo.findBySemesterAndLecturer(semester, lecturer);
        if (confirmation != null && !confirmation.getStatus().equals(TimetableStatus.DRAFT)) {
            return false;
        }
        return true;
    }

    private boolean isOnlineSubject(com.fpt.edu.schedule.model.Subject subject) {
        return Character.isAlphabetic(subject.getCode().charAt(subject.getCode().length() - 1));
    }

    private List<Lecturer> getLecturersNotDraft(Semester semester) {
        List<Lecturer> lecturersNotSendResource = confirmationRepo.findAllBySemester(semester)
                .stream()
                .filter(x -> !x.getStatus().equals(TimetableStatus.DRAFT))
                .map(Confirmation::getLecturer)
                .collect(Collectors.toList());
        return lecturersNotSendResource;
    }

    private void convertData(Vector<Teacher> teachers, Vector<Subject> subjects,
                             Vector<com.fpt.edu.schedule.ai.lib.Class> classes, Vector<ExpectedSlot> expectedSlots,
                             Vector<ExpectedSubject> expectedSubject,
                             int semesterId, String lecturerId, Vector<SlotGroup> slots, Lecturer lecturer, Semester semester, Timetable timetable) {


        // exclude all timetable detail not draft
        List<Lecturer> lecturersNotSendResource = getLecturersNotDraft(semester);
        List<Integer> lineIdPublic = timetableRepo.findBySemesterAndTempFalse(semester)
                .getTimetableDetails()
                .stream()
                .filter(i -> lecturersNotSendResource.contains(i.getLecturer()))
                .map(TimetableDetail::getLineId)
                .collect(Collectors.toList());

        List<TimetableDetail> timetableDetails = timetable.getTimetableDetails()
                .stream()
                .filter(i -> i.getSubject().getDepartment().equals(lecturer.getDepartment()) && !isOnlineSubject(i.getSubject()) && !lineIdPublic.contains(i.getLineId()))
                .collect(Collectors.toList());
        List<Expected> expected = expectedRepo.findAllBySemester(semester);
        List<com.fpt.edu.schedule.model.Subject> subjectsFromDb = subjectService.getAllSubjectBySemester(semesterId, lecturerId);
        //teacher model
        List<Lecturer> lecturers = lecturerRepo.findAllByDepartmentAndStatus(lecturer.getDepartment(), StatusLecturer.ACTIVATE).stream()
                .filter(i -> expectedRepo.findBySemesterAndLecturer(semester, i) != null && isDraft(i, semester))
                .collect(Collectors.toList());
        lecturers.forEach(i -> {
            Expected expectedEach = expectedRepo.findBySemesterAndLecturer(semester, i);
            teachers.add(new Teacher(i.getEmail(), i.getFullName(), i.getId(), i.isFullTime() ? 1 : 0, expectedEach.getExpectedNote().getExpectedNumOfClass(), expectedEach.getExpectedNote().getMaxConsecutiveSlot(), i.getQuotaClass()));
        });
        //expected model
        expected.stream()
                .filter(i -> lecturers.contains(i.getLecturer()))
                .forEach(i -> {
                    i.getExpectedSlots().forEach(s -> {
                        expectedSlots.add(new ExpectedSlot(s.getExpected().getLecturer().getId(), slotRepository.findByName(s.getSlotName()).getId(), s.getLevelOfPrefer()));
                    });
                    i.getExpectedSubjects().stream().filter(x -> !isOnlineSubject(subjectRepo.findByCode(x.getSubjectCode()))).forEach(s -> {
                        expectedSubject.add(new ExpectedSubject(s.getExpected().getLecturer().getId(), subjectRepo.findByCode(s.getSubjectCode()).getId(), s.getLevelOfPrefer()));
                    });
                });
        //class model
        timetableDetails.stream().forEach(i -> {
            classes.add(new Class(i.getClassName().getName(), i.getSlot().getId(), i.getSubject().getId(), new Room(i.getRoom().getName()), i.getId(), Class.OK));
        });
        //subject model
        // exclude online subject
        subjectsFromDb.stream().filter(i -> !isOnlineSubject(i)).forEach(i -> {
            subjects.add(new Subject(i.getCode(), i.getId()));
        });

        //slot model
        SlotGroup m246 = new SlotGroup(3, 0);
        m246.addSlot(new com.fpt.edu.schedule.ai.lib.Slot("M1", 1));
        m246.addSlot(new com.fpt.edu.schedule.ai.lib.Slot("M2", 2));
        m246.addSlot(new com.fpt.edu.schedule.ai.lib.Slot("M3", 3));
        SlotGroup e246 = new SlotGroup(3, 1);

        e246.addSlot(new com.fpt.edu.schedule.ai.lib.Slot("E1", 6));
        e246.addSlot(new com.fpt.edu.schedule.ai.lib.Slot("E2", 7));
        e246.addSlot(new com.fpt.edu.schedule.ai.lib.Slot("E3", 8));

        SlotGroup m35 = new SlotGroup(2, 2);

        m35.addSlot(new com.fpt.edu.schedule.ai.lib.Slot("M4", 4));
        m35.addSlot(new com.fpt.edu.schedule.ai.lib.Slot("M5", 5));

        SlotGroup e35 = new SlotGroup(2, 3);

        e35.addSlot(new com.fpt.edu.schedule.ai.lib.Slot("E4", 9));
        e35.addSlot(new com.fpt.edu.schedule.ai.lib.Slot("E5", 10));
        slots.add(m246);
        slots.add(e246);
        slots.add(m35);
        slots.add(e35);


    }

}

