import numpy as np
from sklearn.metrics import log_loss

from tools.advanced_validation import _metrics


def test_validation_baseline_uses_training_prevalence_not_test_prevalence():
    y_test = np.array([1, 1, 1, 0, 0], dtype=int)
    probability = np.array([0.7, 0.6, 0.4, 0.3, 0.2])
    training_prevalence = 0.20

    result = _metrics(y_test, probability, training_prevalence)

    expected = log_loss(y_test, np.full(len(y_test), training_prevalence), labels=[0, 1])
    assert result["baseline_train_prevalence"] == 0.20
    assert result["baseline_log_loss"] == round(expected, 4)
