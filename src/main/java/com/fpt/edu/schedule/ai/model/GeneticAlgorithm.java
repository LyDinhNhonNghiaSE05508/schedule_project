package com.fpt.edu.schedule.ai.model;


import com.fpt.edu.schedule.ai.lib.Record;
import com.fpt.edu.schedule.ai.lib.Slot;
import com.fpt.edu.schedule.ai.lib.SlotGroup;
import com.fpt.edu.schedule.common.enums.Constant;
import com.fpt.edu.schedule.dto.Runs;
import com.fpt.edu.schedule.dto.TimetableDetailDTO;
import com.fpt.edu.schedule.dto.TimetableEdit;
import com.fpt.edu.schedule.event.ResponseEvent;
import com.fpt.edu.schedule.model.TimetableDetail;
import com.fpt.edu.schedule.repository.base.LecturerRepository;
import com.fpt.edu.schedule.repository.base.TimetableDetailRepository;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Setter
@Getter
public class GeneticAlgorithm {
    public static final int POPULATION_SIZE = 1000;
    public static final double MUTATION_RATE = 0.25;
    public static final int TOURNAMENT_SIZE = 3;
    public static final int CLASS_NUMBER = 5;
    public static final double IN_CLASS_RATE = 0.9;
    @Autowired
    TimetableDetailRepository timetableDetailRepository;
    @Autowired
    LecturerRepository lecturerRepository;
    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    Population population;
    @Autowired
    Model model;
    private int generation;
    private Train train;
    private boolean isRun = true;
    private String lecturerId;
    Map<Integer, Runs> genInfos = new HashMap<>();

    public GeneticAlgorithm() {
    }

    public void updateFitness() {
      System.out.println(this.lecturerId);
        this.population.updateFitness();
        this.generation++;
        System.out.println("Fitness Average: " + this.population.getAverageFitness());
        System.out.println("Best fitness: " + this.population.getBestIndividuals().getFitness());
        System.out.println("Generation: " + this.generation);
        handleTimetable();
        this.train.notify(this.population.getBestIndividuals(), this.population.getBestIndividuals().getFitness(), this.population.getAverageFitness(),
                this.population.getBestIndividuals().getNumberOfViolation());
    }

    public Chromosome selectParent() {
        Random random = new Random();
        Vector<Chromosome> candidates = new Vector<>();
        for (int i = 0; i < TOURNAMENT_SIZE; i++) {
            int idx = random.nextInt(POPULATION_SIZE);
            candidates.add(this.population.getIndividuals().get(idx));
        }

        double best = candidates.get(0).getFitness();
        Chromosome res = candidates.get(0);
        for (Chromosome chromosome : candidates) {
            if (chromosome.getFitness() > best) {
                best = chromosome.getFitness();
                res = chromosome;
            }
        }
        return res;
    }

    public Chromosome selectParentRandomly() {
        Random random = new Random();
        int idx = random.nextInt(POPULATION_SIZE);
        return this.population.getIndividuals().get(idx);
    }

    public Chromosome selectParent(Vector<Chromosome> individuals) {
        Random random = new Random();
        Vector<Chromosome> candidates = new Vector<>();
        for (int i = 0; i < TOURNAMENT_SIZE; i++) {
            int idx = random.nextInt(individuals.size());
            candidates.add(individuals.get(idx));
        }

        double best = candidates.get(0).getFitness();
        Chromosome res = candidates.get(0);
        for (Chromosome chromosome : candidates) {
            if (chromosome.getFitness() > best) {
                best = chromosome.getFitness();
                res = chromosome;
            }
        }
        return res;
    }

    public void selection1() {
        Population population1 = new Population(this.model);
        for (int i = 0; i < this.POPULATION_SIZE / 2; i++) {
            Chromosome p1 = selectParent();
            Chromosome p2 = selectParent();
            Chromosome c1 = this.crossover(p1, p2);
            Chromosome c2 = this.crossover(p2, p1);
            population1.addIndividual(c1);
            population1.addIndividual(c2);
        }
        this.population = population1;
    }

    public void selection() {
        Population population1 = new Population(this.model);

        this.population.sortByFitnetss();
        Vector<Vector<Chromosome>> individualsByClass = new Vector();
        for (int i = 0; i < CLASS_NUMBER; i++) {
            individualsByClass.add(new Vector());
        }


        int classSize = POPULATION_SIZE / CLASS_NUMBER + ((POPULATION_SIZE % CLASS_NUMBER == 0) ? 0 : 1);
        for (int i = 0; i < POPULATION_SIZE; i++) {
            int classId = i / classSize;
            individualsByClass.get(classId).add(this.population.getIndividuals().get(i));
        }

        for (int i = 0; i < CLASS_NUMBER; i++) {
            Collections.shuffle(individualsByClass.get(i));
        }


        int inClassPairNumber = (int) (POPULATION_SIZE * IN_CLASS_RATE / CLASS_NUMBER / 2);
        for (int i = 0; i < CLASS_NUMBER; i++) {
            for (int j = 0; j < inClassPairNumber; j++) {
                Chromosome p1 = selectParent(individualsByClass.get(i));
                Chromosome p2 = selectParent(individualsByClass.get(i));
                Chromosome c1 = this.crossover(p1, p2);
                Chromosome c2 = this.crossover(p2, p1);
                population1.addIndividual(c1);
                population1.addIndividual(c2);
            }
        }


        while (population1.getSize() < POPULATION_SIZE) {
            Chromosome p1 = selectParentRandomly();
            Chromosome p2 = selectParentRandomly();
            Chromosome c1 = this.crossover(p1, p2);
            Chromosome c2 = this.crossover(p2, p1);
            population1.addIndividual(c1);
            population1.addIndividual(c2);
        }
        this.population = population1;
    }

    public Chromosome crossover(Chromosome c1, Chromosome c2) {
        Vector<Slot> slots = SlotGroup.getSlotList(this.model.getSlots());
        Vector<Vector<Integer>> genes = new Vector<>();
        Random random = new Random();
        for (Slot slot : slots) {
            Vector<Integer> p1 = c1.getGenes().get(slot.getId());
            Vector<Integer> p2 = c2.getGenes().get(slot.getId());
            Vector<Integer> p3 = (new PMX(p1, p2, random.nextInt(Integer.MAX_VALUE))).getChildren();

            genes.add(p3);
        }

        return new Chromosome(this.model, genes);
    }

    public void mutate() {
        Random random = new Random();
        for (int i = 0; i < POPULATION_SIZE; i++) {
            for (int j = 0; j < 1; j++) {
                if (random.nextDouble() < MUTATION_RATE) {
                    this.population.getIndividuals().get(i).mutate();
                }
            }
            this.population.getIndividuals().get(i).autoRepair();
        }
    }
    @Async
    public void start() {
        while (true) {
            if(!this.isRun){
                publisher.publishEvent(new ResponseEvent(this,this.population.getBestIndividuals(),Constant.stopGa,this.generation));
                break;
            }
            this.updateFitness();
            this.selection1();
            this.mutate();
        }
    }
    public void stop(){
        this.isRun =false;
    }

    public void handleTimetable(){
        List<TimetableDetail> timetableDetails = new ArrayList<>();
        Vector<Record> records = population.getBestIndividuals().getSchedule();
        records.forEach(i -> {
            TimetableDetail timetableDetail = timetableDetailRepository.findById(i.getClassId());
            timetableDetail.setLecturer(lecturerRepository.findById(i.getTeacherId()));
            timetableDetails.add(timetableDetail);
        });
        List<TimetableDetailDTO> timetableDetailDTOS = timetableDetails.stream().distinct().map(i -> new TimetableDetailDTO(i.getId(), i.getLecturer() != null ? i.getLecturer().getShortName() : null, i.getRoom() != null ? i.getRoom().getName() : "NOT_ASSIGN",
                i.getClassName().getName(), i.getSlot().getName(), i.getSubject().getCode())).collect(Collectors.toList());
        Map<String, List<TimetableDetailDTO>> collect = timetableDetailDTOS.stream().collect(Collectors.groupingBy(TimetableDetailDTO::getRoom));
        List<TimetableEdit> timetableEdits = collect.entrySet().stream().map(i -> new TimetableEdit(i.getKey(), i.getValue())).collect(Collectors.toList());
        timetableEdits.sort(Comparator.comparing(TimetableEdit::getRoom));
        Runs run = new Runs(this.population.getBestIndividuals().getFitness(),this.population.getAverageFitness(),this.population.getBestIndividuals().getNumberOfViolation(),0,this.generation,this.generation,timetableEdits);
        genInfos.put(this.generation,run);
    }
}
