package uk.co.thinkofdeath.patchtools.matching;

import uk.co.thinkofdeath.patchtools.PatchScope;
import uk.co.thinkofdeath.patchtools.patch.*;
import uk.co.thinkofdeath.patchtools.wrappers.ClassSet;
import uk.co.thinkofdeath.patchtools.wrappers.ClassWrapper;
import uk.co.thinkofdeath.patchtools.wrappers.FieldWrapper;
import uk.co.thinkofdeath.patchtools.wrappers.MethodWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

public class MatchGenerator {

    private ExecutorCompletionService<PatchScope> executorCompletionService
            = new ExecutorCompletionService<>(Executors.newFixedThreadPool(4));

    private final ClassSet classSet;
    private final PatchClasses patchClasses;
    private final PatchScope scope;

    private final Map<Object, Integer> state = new HashMap<>();

    private final List<Object> tickList = new ArrayList<>();

    public MatchGenerator(ClassSet classSet, PatchClasses patchClasses, PatchScope scope) {
        this.classSet = classSet;
        this.patchClasses = patchClasses;
        this.scope = scope;

        patchClasses.getClasses()
                .stream()
                .filter(c -> c.getMode() != Mode.ADD)
                .forEach(c -> {
                    tickList.add(c);

                    c.getMethods().stream()
                            .filter(m -> m.getMode() != Mode.ADD)
                            .forEach(tickList::add);

                    c.getFields().stream()
                            .filter(f -> f.getMode() != Mode.ADD)
                            .forEach(tickList::add);
                });
    }

    public PatchScope apply(Predicate<PatchScope> test) {
        long start = System.nanoTime();
        ArrayList<Future<PatchScope>> tasks = new ArrayList<>();
        try {
            while (true) {
                Future<PatchScope> retScope = executorCompletionService.poll();
                if (retScope != null) {
                    tasks.remove(retScope);
                    if (retScope.get() != null) {
                        return retScope.get();
                    }
                }

                PatchScope newScope = scope.duplicate();
                try {
                    tickList.forEach(v -> {
                        int index = getState(v);
                        if (v instanceof PatchClass) {
                            PatchClass pc = (PatchClass) v;
                            String[] classes = classSet.classes(true);
                            if (classes.length <= index) {
                                throw new IllegalStateException();
                            }
                            if (newScope.hasClass(classSet.getClassWrapper(classes[index]))) {
                                throw new IllegalStateException();
                            }
                            newScope.putClass(classSet.getClassWrapper(classes[index]), pc.getIdent().getName());
                        } else if (v instanceof PatchMethod) {
                            PatchMethod pm = (PatchMethod) v;
                            ClassWrapper cls = newScope.getClass(pm.getOwner().getIdent().getName());
                            MethodWrapper[] methods = cls.getMethods(true);
                            if (methods.length <= index) {
                                throw new IllegalStateException();
                            }
                            if (newScope.hasMethod(methods[index])) {
                                throw new IllegalStateException();
                            }
                            newScope.putMethod(methods[index], pm.getIdent().getName(), pm.getDesc().getDescriptor());
                        } else if (v instanceof PatchField) {
                            PatchField pf = (PatchField) v;
                            ClassWrapper cls = newScope.getClass(pf.getOwner().getIdent().getName());
                            FieldWrapper[] fields = cls.getFields(true);
                            if (fields.length <= index) {
                                throw new IllegalStateException();
                            }
                            if (newScope.hasField(fields[index])) {
                                throw new IllegalStateException();
                            }
                            newScope.putField(fields[index], pf.getIdent().getName(), pf.getDesc().getDescriptor());
                        }
                    });
                } catch (IllegalStateException e) {
                    if (tick()) {
                        continue;
                    }
                    break;
                }

                tasks.add(executorCompletionService.submit(() -> {
                    if (test.test(newScope)) {
                        return newScope;
                    }
                    return null;
                }));

                if (tick()) {
                    continue;
                }
                break;
            }
            while (!tasks.isEmpty()) {
                Future<PatchScope> retScope = executorCompletionService.take();
                if (retScope != null) {
                    tasks.remove(retScope);
                    if (retScope.get() != null) {
                        return retScope.get();
                    }
                }
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } finally {
            for (Future<PatchScope> f : tasks) {
                f.cancel(true);
            }
        }
    }

    private boolean tick() {
        for (int i = tickList.size() - 1; i >= 0; i--) {
            Object val = tickList.get(i);
            int index = getState(val);
            index++;
            if (val instanceof PatchClass) {
                if (index >= classSet.classes(true).length) {
                    index = 0;
                    state.put(val, index);
                    if (i == 0) {
                        break;
                    }
                    continue;
                }
            } else if (val instanceof PatchMethod) {
                PatchClass owner = nearestClass(i);
                ClassWrapper cls = classSet.getClassWrapper(classSet.classes(true)[getState(owner)]);
                if (index >= cls.getMethods(true).length) {
                    index = 0;
                    state.put(val, index);
                    if (i == 0) {
                        break;
                    }
                    continue;
                }
            } else if (val instanceof PatchField) {
                PatchClass owner = nearestClass(i);
                ClassWrapper cls = classSet.getClassWrapper(classSet.classes(true)[getState(owner)]);
                if (index >= cls.getFields(true).length) {
                    index = 0;
                    state.put(val, index);
                    if (i == 0) {
                        break;
                    }
                    continue;
                }
            }
            state.put(val, index);
            return true;
        }
        return false;
    }

    private PatchClass nearestClass(int i) {
        for (; i >= 0; i--) {
            Object val = tickList.get(i);
            if (val instanceof PatchClass) {
                return (PatchClass) val;
            }
        }
        return null;
    }

    private int getState(Object o) {
        if (!state.containsKey(o)) {
            state.put(o, 0);
        }
        return state.get(o);
    }
}
